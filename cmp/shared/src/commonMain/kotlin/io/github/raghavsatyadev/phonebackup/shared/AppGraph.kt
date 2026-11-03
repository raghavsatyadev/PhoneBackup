package io.github.raghavsatyadev.phonebackup.shared

import io.github.raghavsatyadev.phonebackup.shared.data.GalleryClient
import io.github.raghavsatyadev.phonebackup.shared.data.PhotoSender
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.shared.data.ShareDraft
import io.github.raghavsatyadev.phonebackup.shared.data.ShareQueue
import io.github.raghavsatyadev.phonebackup.shared.data.UploadClient
import io.github.raghavsatyadev.phonebackup.shared.data.authedImageClient
import io.github.raghavsatyadev.phonebackup.shared.data.createHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-wired app dependencies; one instance lives in the platform Application. */
class AppGraph(val settings: SettingsStore) {
  private val http = createHttpClient()
  /** App-wide scope: work here outlives any screen. */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val uploads = UploadClient(http)
  val gallery = GalleryClient(http)
  val queue by lazy {
    val sender = PhotoSender { config, photo, album, tags, onProgress ->
      uploads
        .upload(config, photo.path, photo.fileName, photo.sizeBytes, album, tags, photo.takenAt) { done, total ->
          if (total > 0) onProgress(done.toFloat() / total)
        }
        .skipped
    }
    ShareQueue(scope, sender) { settings.saveDraft(ShareDraft()) }
  }
  /** Used by the image loader for gallery thumbnails; sends the token in a header. */
  val imageHttp by lazy { authedImageClient(settings) }
}
