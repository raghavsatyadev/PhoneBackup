package io.github.raghavsatyadev.phonebackup.desktop.system

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Keeps one copy of the app running. The first copy listens on a local port; a second copy tells
 * it to show its window and quits, so opening the app from the Start menu brings it out of the tray.
 */
object SingleInstance {
  private const val PORT = 9878
  private const val SHOW = "show"

  /** True when this is the only copy; [onShow] runs (on a background thread) when another starts. */
  fun claim(onShow: () -> Unit): Boolean {
    val server =
      try {
        ServerSocket(PORT, 4, InetAddress.getLoopbackAddress())
      } catch (_: Exception) {
        return !askToShow()
      }
    Thread(
        {
          while (true) {
            val text = runCatching { server.accept().use { it.getInputStream().bufferedReader().readLine() } }.getOrNull()
            if (text == SHOW) onShow()
          }
        },
        "single-instance",
      )
      .apply { isDaemon = true }
      .start()
    return true
  }

  /** True when a running copy got the message. */
  private fun askToShow(): Boolean =
    runCatching {
        Socket(InetAddress.getLoopbackAddress(), PORT).use { it.getOutputStream().write("$SHOW\n".toByteArray()) }
      }
      .isSuccess
}
