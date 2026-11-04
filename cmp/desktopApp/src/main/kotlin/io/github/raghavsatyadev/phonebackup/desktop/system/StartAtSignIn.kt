package io.github.raghavsatyadev.phonebackup.desktop.system

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/** Starts the installed app, hidden in the tray, when the user signs in to Windows. */
object StartAtSignIn {
  const val HIDDEN_ARG = "--minimized"
  private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
  private const val VALUE = "PhoneBackup"

  /** Points the sign-in entry at this exe. Skipped when running from Gradle (java.exe). */
  fun register() {
    if (!Platform.isWindows()) return
    val exe = ProcessHandle.current().info().command().orElse(null) ?: return
    if (exe.substringAfterLast('\\').lowercase().startsWith("java")) return
    runCatching {
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE, "\"$exe\" $HIDDEN_ARG")
      }
      .onFailure { System.err.println("Could not register start at sign-in: $it") }
  }
}
