package io.github.raghavsatyadev.phonebackup.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.raghavsatyadev.phonebackup.shared.AppGraph
import io.github.raghavsatyadev.phonebackup.shared.ViewerApp
import io.github.raghavsatyadev.phonebackup.shared.data.DesktopKeyValueStore
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore

/** PhoneBackup Viewer: browse the photos on the PhoneBackup server from a desktop window. */
fun main() {
  val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: System.getProperty("user.name").orEmpty()
  val graph = AppGraph(SettingsStore(DesktopKeyValueStore()), deviceName = host)
  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "PhoneBackup",
      state = rememberWindowState(width = 1280.dp, height = 860.dp),
    ) {
      ViewerApp(graph)
    }
  }
}
