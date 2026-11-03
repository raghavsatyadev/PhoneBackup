package io.github.raghavsatyadev.phonebackup.desktop

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.raghavsatyadev.phonebackup.shared.AppGraph
import io.github.raghavsatyadev.phonebackup.shared.ViewerApp
import io.github.raghavsatyadev.phonebackup.shared.data.DesktopKeyValueStore
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** PhoneBackup Viewer: browse the photos on the PhoneBackup server from a desktop window. */
fun main() {
  val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: System.getProperty("user.name").orEmpty()
  val settings = SettingsStore(DesktopKeyValueStore())
  addThisPc(settings)
  val graph = AppGraph(settings, deviceName = host, pickFolder = ::chooseFolder)
  val icon = appIcon()
  application {
    Window(
      onCloseRequest = ::exitApplication,
      title = "PhoneBackup",
      icon = icon,
      state = rememberWindowState(width = 1280.dp, height = 860.dp),
    ) {
      ViewerApp(graph)
    }
  }
}

private fun appIcon(): Painter? =
  Thread.currentThread().contextClassLoader.getResourceAsStream("icon.svg")?.use { loadSvgPainter(it, Density(1f)) }

/** The system folder chooser, starting at [start]; null when cancelled. */
private suspend fun chooseFolder(start: String): String? =
  withContext(Dispatchers.Swing) {
    val chooser =
      JFileChooser(File(start).takeIf { it.isDirectory } ?: File(start).parentFile).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Folder for photos from phones"
      }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
  }
