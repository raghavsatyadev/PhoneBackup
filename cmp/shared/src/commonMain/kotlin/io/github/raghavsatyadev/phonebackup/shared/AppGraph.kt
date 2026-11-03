package io.github.raghavsatyadev.phonebackup.shared

import io.github.raghavsatyadev.phonebackup.shared.data.GalleryClient
import io.github.raghavsatyadev.phonebackup.shared.data.NetworkServers
import io.github.raghavsatyadev.phonebackup.shared.data.NoDiscovery
import io.github.raghavsatyadev.phonebackup.shared.data.PairClient
import io.github.raghavsatyadev.phonebackup.shared.data.ServerDiscovery
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
/** [deviceName] is what the PC shows when this device asks to pair. */
class AppGraph(
  val settings: SettingsStore,
  discovery: ServerDiscovery = NoDiscovery,
  val deviceName: String = "",
  /**
   * Opens a folder chooser (starting at the given path) and returns the pick, or null. Only the
   * desktop app passes one: changing where the PC saves photos is never offered on the phone.
   */
  val pickFolder: (suspend (String) -> String?)? = null,
) {
  private val http = createHttpClient()
  /** App-wide scope: work here outlives any screen. */
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val uploads = UploadClient(http)
  val gallery = GalleryClient(http)
  val pairing = PairClient(http)
  val network = NetworkServers(discovery, settings)
  /** False on the desktop viewer: PCs are only added by address there. */
  val canSearch = discovery !== NoDiscovery
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
