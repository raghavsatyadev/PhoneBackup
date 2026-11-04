package io.github.raghavsatyadev.phonebackup.desktop.system

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One entry in the tray icon's right-click menu. */
data class TrayMenuItem(val label: String, val action: () -> Unit)

/** The app's icon in the notification area, which also shows its notifications. */
interface TrayIcon : AutoCloseable {
  /** `false` where there is no icon to bring the app back from, so closing the window must quit. */
  val available: Boolean

  /** Shows a notification next to the icon; a click on it calls the icon's `onOpen`. */
  fun notify(title: String, text: String, sound: Boolean)

  companion object {
    /**
     * The icon for this system: the Win32 one on Windows, none elsewhere
     * (notifications are then only logged). [onOpen] runs on the icon's thread when the icon or a
     * notification is clicked; a right click shows [menu].
     */
    fun create(tooltip: String, menu: List<TrayMenuItem>, onOpen: () -> Unit): TrayIcon =
      when {
        Platform.isWindows() -> WindowsTrayIcon(tooltip, menu, onOpen).also { it.start() }
        else -> LoggingTrayIcon
      }
  }
}

/** Where there is no tray icon: notifications go to the log. */
private object LoggingTrayIcon : TrayIcon {
  override val available = false

  override fun notify(title: String, text: String, sound: Boolean) = println("$title: $text")

  override fun close() = Unit
}

/**
 * A Win32 notification-area icon on its own message thread. Unlike AWT's `TrayIcon`, it can show a
 * notification without a sound (`NIIF_NOSOUND`) and looks native.
 */
internal class WindowsTrayIcon(
  private val tooltip: String,
  private val menu: List<TrayMenuItem>,
  private val onOpen: () -> Unit,
) : TrayIcon {
  private val log = TrayLog
  private val ready = CountDownLatch(1)
  @Volatile private var hwnd: WinDef.HWND? = null
  private var icon: WinDef.HICON? = null
  private var ownsIcon = false
  private val taskbarCreated = User32.INSTANCE.RegisterWindowMessage("TaskbarCreated")

  // Kept in a field: JNA callbacks must stay reachable while Windows can call them.
  private val windowProc = WinUser.WindowProc { window, message, wParam, lParam ->
    handle(window, message, wParam, lParam)
  }

  override val available: Boolean
    get() = hwnd != null

  fun start() {
    Thread(::run, "tray-icon").apply { isDaemon = true }.start()
    if (!ready.await(START_TIMEOUT_S, TimeUnit.SECONDS)) log.warn("Tray icon did not start")
  }

  override fun notify(title: String, text: String, sound: Boolean) {
    val window = hwnd ?: return log.warn("No tray icon for notification '{}'", title)
    val data = data(window, NIF_INFO)
    data.szInfoTitle.put(title)
    data.szInfo.put(text)
    data.dwInfoFlags = NIIF_INFO or (if (sound) 0 else NIIF_NOSOUND)
    if (!Shell.INSTANCE.Shell_NotifyIconW(NIM_MODIFY, data)) log.warn("Notification failed")
  }

  override fun close() {
    val window = hwnd ?: return
    Shell.INSTANCE.Shell_NotifyIconW(NIM_DELETE, data(window, 0))
    User32.INSTANCE.PostMessage(window, WinUser.WM_CLOSE, null, null)
  }

  private fun run() {
    try {
      val instance = Kernel32.INSTANCE.GetModuleHandle(null)
      val windowClass =
        WinUser.WNDCLASSEX().apply {
          hInstance = instance
          lpfnWndProc = windowProc
          lpszClassName = WINDOW_CLASS
        }
      User32.INSTANCE.RegisterClassEx(windowClass)
      // A hidden top-level window rather than a message-only one: only top-level windows get the
      // TaskbarCreated broadcast after Explorer restarts.
      val window =
        User32.INSTANCE.CreateWindowEx(
          0,
          WINDOW_CLASS,
          tooltip,
          0,
          0,
          0,
          0,
          0,
          null,
          null,
          instance,
          null,
        )
      icon = loadIcon()
      hwnd = window
      addIcon(window)
    } catch (e: Throwable) {
      log.error("Could not create the tray icon", e)
    } finally {
      ready.countDown()
    }
    val message = WinUser.MSG()
    while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
      User32.INSTANCE.TranslateMessage(message)
      User32.INSTANCE.DispatchMessage(message)
    }
    if (ownsIcon) User32.INSTANCE.DestroyIcon(icon)
  }

  private fun addIcon(window: WinDef.HWND) {
    val data = data(window, NIF_MESSAGE or NIF_ICON or NIF_TIP or NIF_SHOWTIP)
    data.uCallbackMessage = CALLBACK_MESSAGE
    data.hIcon = icon
    data.szTip.put(tooltip)
    if (!Shell.INSTANCE.Shell_NotifyIconW(NIM_ADD, data)) log.warn("Could not add the tray icon")
    data.uVersion = NOTIFYICON_VERSION_4
    Shell.INSTANCE.Shell_NotifyIconW(NIM_SETVERSION, data)
  }

  private fun handle(
    window: WinDef.HWND,
    message: Int,
    wParam: WinDef.WPARAM,
    lParam: WinDef.LPARAM,
  ): WinDef.LRESULT {
    when (message) {
      CALLBACK_MESSAGE ->
        when (lParam.toInt() and LOW_WORD) {
          NIN_BALLOONUSERCLICK,
          NIN_SELECT,
          NIN_KEYSELECT -> open()
          WM_CONTEXTMENU -> showMenu(window)
        }
      taskbarCreated -> addIcon(window)
      WinUser.WM_CLOSE -> User32.INSTANCE.DestroyWindow(window)
      WinUser.WM_DESTROY -> User32.INSTANCE.PostQuitMessage(0)
      else -> return User32.INSTANCE.DefWindowProc(window, message, wParam, lParam)
    }
    return WinDef.LRESULT(0)
  }

  private fun open() = run("Opening the app from the tray", onOpen)

  /** The right-click menu at the cursor; the chosen item runs on this thread. */
  private fun showMenu(window: WinDef.HWND) {
    val popup = Menus.INSTANCE.CreatePopupMenu() ?: return
    try {
      menu.forEachIndexed { index, item ->
        Menus.INSTANCE.AppendMenuW(popup, MF_STRING, FIRST_MENU_ID + index, item.label)
      }
      val cursor = WinDef.POINT()
      User32.INSTANCE.GetCursorPos(cursor)
      // Without this the menu stays open when the user clicks elsewhere.
      User32.INSTANCE.SetForegroundWindow(window)
      val chosen =
        Menus.INSTANCE.TrackPopupMenu(
          popup,
          TPM_RETURNCMD or TPM_RIGHTBUTTON or TPM_BOTTOMALIGN,
          cursor.x,
          cursor.y,
          0,
          window,
          null,
        )
      User32.INSTANCE.PostMessage(window, WM_NULL, null, null)
      menu.getOrNull(chosen - FIRST_MENU_ID)?.let { run("Tray menu '${it.label}'", it.action) }
    } finally {
      Menus.INSTANCE.DestroyMenu(popup)
    }
  }

  private fun run(what: String, action: () -> Unit) =
    try {
      action()
    } catch (e: Exception) {
      log.error("{} failed", what, e)
    }

  /** The running program's icon (the app's own once packaged), or the stock application icon. */
  private fun loadIcon(): WinDef.HICON? {
    val exe = ProcessHandle.current().info().command().orElse(null)
    if (exe != null) {
      val small = arrayOfNulls<WinDef.HICON>(1)
      if (Shell32.INSTANCE.ExtractIconEx(exe, 0, null, small, 1) > 0 && small[0] != null) {
        ownsIcon = true
        return small[0]
      }
    }
    return Icons.INSTANCE.LoadIconW(null, Pointer.createConstant(IDI_APPLICATION))
  }

  private fun data(window: WinDef.HWND, flags: Int) =
    NotifyIconData().apply {
      hWnd = window
      uID = ICON_ID
      uFlags = flags
    }

  private fun CharArray.put(text: String) {
    val length = minOf(text.length, size - 1)
    text.toCharArray(this, 0, 0, length)
    this[length] = 0.toChar()
  }

  /** `NOTIFYICONDATAW`. */
  @Structure.FieldOrder(
    "cbSize",
    "hWnd",
    "uID",
    "uFlags",
    "uCallbackMessage",
    "hIcon",
    "szTip",
    "dwState",
    "dwStateMask",
    "szInfo",
    "uVersion",
    "szInfoTitle",
    "dwInfoFlags",
    "guidItem",
    "hBalloonIcon",
  )
  class NotifyIconData : Structure() {
    @JvmField var cbSize: Int = 0
    @JvmField var hWnd: WinDef.HWND? = null
    @JvmField var uID: Int = 0
    @JvmField var uFlags: Int = 0
    @JvmField var uCallbackMessage: Int = 0
    @JvmField var hIcon: WinDef.HICON? = null
    @JvmField var szTip: CharArray = CharArray(128)
    @JvmField var dwState: Int = 0
    @JvmField var dwStateMask: Int = 0
    @JvmField var szInfo: CharArray = CharArray(256)
    /** A union with `uTimeout`, which Windows ignores since Vista. */
    @JvmField var uVersion: Int = 0
    @JvmField var szInfoTitle: CharArray = CharArray(64)
    @JvmField var dwInfoFlags: Int = 0
    @JvmField var guidItem: Guid.GUID = Guid.GUID()
    @JvmField var hBalloonIcon: WinDef.HICON? = null

    init {
      cbSize = size()
    }
  }

  @Suppress("FunctionName")
  private interface Shell : StdCallLibrary {
    fun Shell_NotifyIconW(message: Int, data: NotifyIconData): Boolean

    companion object {
      val INSTANCE: Shell = Native.load("shell32", Shell::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
  }

  @Suppress("FunctionName")
  private interface Icons : StdCallLibrary {
    fun LoadIconW(instance: WinDef.HINSTANCE?, name: Pointer): WinDef.HICON?

    companion object {
      val INSTANCE: Icons = Native.load("user32", Icons::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
  }

  @Suppress("FunctionName")
  private interface Menus : StdCallLibrary {
    fun CreatePopupMenu(): WinDef.HMENU?

    fun AppendMenuW(menu: WinDef.HMENU, flags: Int, id: Int, text: String): Boolean

    fun TrackPopupMenu(
      menu: WinDef.HMENU,
      flags: Int,
      x: Int,
      y: Int,
      reserved: Int,
      window: WinDef.HWND,
      rect: Pointer?,
    ): Int

    fun DestroyMenu(menu: WinDef.HMENU): Boolean

    companion object {
      val INSTANCE: Menus = Native.load("user32", Menus::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
  }

  private companion object {
    const val WINDOW_CLASS = "phonebackup.tray"
    const val ICON_ID = 1
    const val START_TIMEOUT_S = 5L
    const val WM_APP = 0x8000
    const val CALLBACK_MESSAGE = WM_APP + 1
    const val LOW_WORD = 0xFFFF
    const val IDI_APPLICATION = 32512L

    const val NIM_ADD = 0
    const val NIM_MODIFY = 1
    const val NIM_DELETE = 2
    const val NIM_SETVERSION = 4
    const val NIF_MESSAGE = 0x1
    const val NIF_ICON = 0x2
    const val NIF_TIP = 0x4
    const val NIF_INFO = 0x10
    const val NIF_SHOWTIP = 0x80
    const val NIIF_INFO = 0x1
    const val NIIF_NOSOUND = 0x10
    const val NOTIFYICON_VERSION_4 = 4
    const val NIN_SELECT = WinUser.WM_USER
    const val NIN_KEYSELECT = WinUser.WM_USER + 1
    const val NIN_BALLOONUSERCLICK = WinUser.WM_USER + 5
    const val WM_NULL = 0x0000
    const val WM_CONTEXTMENU = 0x007B
    const val MF_STRING = 0x0
    const val TPM_RIGHTBUTTON = 0x2
    const val TPM_BOTTOMALIGN = 0x20
    const val TPM_RETURNCMD = 0x100
    const val FIRST_MENU_ID = 1
  }
}

/** Tray problems are worth a line on stderr but never stop the app. */
private object TrayLog {
  fun warn(message: String, vararg args: Any?) = System.err.println("tray: " + fill(message, args))

  fun error(message: String, vararg args: Any?) {
    System.err.println("tray: " + fill(message, args))
    (args.lastOrNull() as? Throwable)?.printStackTrace()
  }

  private fun fill(message: String, args: Array<out Any?>): String =
    args.filterNot { it is Throwable }.fold(message) { text, arg -> text.replaceFirst("{}", arg.toString()) }
}
