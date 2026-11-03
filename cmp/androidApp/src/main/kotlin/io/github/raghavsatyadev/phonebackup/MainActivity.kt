package io.github.raghavsatyadev.phonebackup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.core.content.IntentCompat
import io.github.raghavsatyadev.phonebackup.shared.App

class MainActivity : ComponentActivity() {
  private val app
    get() = application as PhoneBackupApp

  // The system file picker, not the photo picker: the photo picker hides the real file
  // names (it reports "<id>.jpg"), and the name is a date clue for photos without EXIF.
  private val picker =
    registerForActivityResult(OpenMultipleDocuments()) { uris -> app.importPhotos(uris.take(MAX_PICK)) }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // On recreation the share was already imported; a relaunch from Recents replays the old intent.
    val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    if (savedInstanceState == null && !fromHistory) handleShare(intent)
    setContent {
      App(
        graph = app.graph,
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

  private companion object {
    const val MAX_PICK = 100
  }
}
