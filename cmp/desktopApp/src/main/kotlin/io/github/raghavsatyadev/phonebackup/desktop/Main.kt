package io.github.raghavsatyadev.phonebackup.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.raghavsatyadev.phonebackup.desktop.server.PairingDialog
import io.github.raghavsatyadev.phonebackup.desktop.server.PairingRequests
import io.github.raghavsatyadev.phonebackup.desktop.server.ServerProcess
import io.github.raghavsatyadev.phonebackup.desktop.system.SingleInstance
import io.github.raghavsatyadev.phonebackup.desktop.system.StartAtSignIn
import io.github.raghavsatyadev.phonebackup.desktop.system.TrayIcon
import io.github.raghavsatyadev.phonebackup.desktop.system.TrayMenuItem
import io.github.raghavsatyadev.phonebackup.shared.ViewerApp
import io.github.raghavsatyadev.phonebackup.shared.appModule
import io.github.raghavsatyadev.phonebackup.shared.data.DesktopKeyValueStore
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.AppTheme
import java.awt.Desktop
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.koin.core.context.startKoin

/**
 * PhoneBackup for Windows: runs the PhoneBackup server in the background and shows its photos.
 * Closing the window keeps it in the tray (the server keeps taking photos); Quit in the tray menu
 * stops both. Starts hidden at sign-in.
 */
fun main(args: Array<String>) {
  val visible = MutableStateFlow(StartAtSignIn.HIDDEN_ARG !in args)
  if (!SingleInstance.claim { visible.value = true }) exitProcess(0)
  val server = ServerProcess()
  if (!server.start()) System.err.println("The PhoneBackup server did not start; see ${server.logFile}")
  StartAtSignIn.register()

  val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: System.getProperty("user.name").orEmpty()
  val settings = SettingsStore(DesktopKeyValueStore())
  addThisPc(settings) // after the server is up: it writes the token on first start
  startKoin { modules(appModule(settings, deviceName = host, pickFolder = ::chooseFolder)) }

  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val pairing = PairingRequests(scope).also { it.start() }
  val quits = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val tray =
    TrayIcon.create(
      "PhoneBackup",
      listOf(
        TrayMenuItem("Open PhoneBackup") { visible.value = true },
        TrayMenuItem("Open web dashboard") { openDashboard() },
        TrayMenuItem("Quit") { quits.tryEmit(Unit) },
      ),
    ) {
      visible.value = true
    }
  if (!tray.available) visible.value = true
  val icon = appIcon()
  try {
    application(exitProcessOnExit = false) {
      val shown by visible.collectAsState()
      var toldAboutTray by remember { mutableStateOf(false) }
      LaunchedEffect(Unit) { quits.collect { exitApplication() } }
      LaunchedEffect(Unit) {
        // A phone asks to pair: say so and bring the window up, where the dialog is.
        var known = emptySet<String>()
        pairing.pending.drop(1).collect { list ->
          val new = list.filter { it.id !in known }
          known = list.map { it.id }.toSet()
          new.firstOrNull()?.let {
            tray.notify("Allow ${it.name.ifBlank { "a phone" }}?", "Code ${it.code}. Open PhoneBackup to answer.", sound = true)
            visible.value = true
          }
        }
      }
      Window(
        onCloseRequest = {
          if (tray.available) {
            visible.value = false
            if (!toldAboutTray) {
              toldAboutTray = true
              tray.notify("PhoneBackup is still running", "Phones can keep sending photos. Quit from the tray icon.", sound = false)
            }
          } else {
            exitApplication()
          }
        },
        visible = shown,
        title = "PhoneBackup",
        icon = icon,
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
      ) {
        LaunchedEffect(shown) { if (shown) window.toFront() }
        ViewerApp()
        AppTheme { PairingDialog(pairing) }
      }
    }
  } finally {
    scope.cancel()
    tray.close()
    server.stop()
  }
  exitProcess(0)
}

private fun openDashboard() {
  runCatching { Desktop.getDesktop().browse(URI("http://localhost:9876")) }
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
