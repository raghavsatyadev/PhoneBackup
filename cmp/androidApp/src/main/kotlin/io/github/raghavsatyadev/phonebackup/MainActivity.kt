package io.github.raghavsatyadev.phonebackup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.content.IntentCompat
import io.github.raghavsatyadev.phonebackup.shared.App

class MainActivity : ComponentActivity() {
  private val app
    get() = application as PhoneBackupApp

  private val picker =
    registerForActivityResult(PickMultipleVisualMedia(MAX_PICK)) { uris -> app.importPhotos(uris) }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // On recreation the share was already imported; a relaunch from Recents replays the old intent.
    val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    if (savedInstanceState == null && !fromHistory) handleShare(intent)
    setContent {
      App(
        graph = app.graph,
        onPickPhotos = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
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

  private companion object {
    const val MAX_PICK = 100
  }
}
