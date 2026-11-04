package io.github.raghavsatyadev.phonebackup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.content.IntentCompat
import io.github.raghavsatyadev.phonebackup.shared.App
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import io.github.raghavsatyadev.phonebackup.discovery.LocalNetworkAccess
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val app
    get() = application as PhoneBackupApp

  // The system file picker, not the photo picker: the photo picker hides the real file
  // names (it reports "<id>.jpg"), and the name is a date clue for photos without EXIF.
  private val picker =
    registerForActivityResult(OpenMultipleDocuments()) { uris -> app.importPhotos(uris.take(MAX_PICK)) }

  private val notificationPermission = registerForActivityResult(RequestPermission()) {}

  // Either answer lets the search start; it only runs when granted.
  private val localNetworkPermission =
    registerForActivityResult(RequestPermission()) { app.localNetwork.onAnswered() }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    askForNotificationsOnFirstTransfer()
    askForLocalNetwork(firstStart = savedInstanceState == null)
    // On recreation the share was already imported; a relaunch from Recents replays the old intent.
    val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    if (savedInstanceState == null && !fromHistory) handleShare(intent)
    setContent {
      App(
        onPickPhotos = { picker.launch(arrayOf("image/*")) },
      )
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleShare(intent)
  }

  private fun handleShare(intent: Intent) {
    val uris: List<Uri> =
      when (intent.action) {
        Intent.ACTION_SEND ->
          listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
          IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
      }
    // Some apps only fill ClipData.
    val clip = intent.clipData
    val all =
      if (uris.isEmpty() && clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
      else uris
    app.importPhotos(all)
  }

  /** Asks once per screen, when a copy or upload first starts, so the progress bar can show. */
  /** Android 17+: asked once, so the app can find PCs without the system's device picker. */
  private fun askForLocalNetwork(firstStart: Boolean) {
    val access = app.localNetwork
    if (!access.needed || access.granted() || !firstStart) {
      access.onAnswered()
      return
    }
    localNetworkPermission.launch(LocalNetworkAccess.PERMISSION)
  }

  private fun askForNotificationsOnFirstTransfer() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    lifecycleScope.launch {
      app.queue.progress.filterNotNull().first()
      val granted =
        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  private companion object {
    const val MAX_PICK = 100
  }
}
