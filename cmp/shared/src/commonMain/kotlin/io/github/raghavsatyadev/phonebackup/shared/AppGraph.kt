package io.github.raghavsatyadev.phonebackup.shared

import io.github.raghavsatyadev.phonebackup.shared.data.GalleryClient
import io.github.raghavsatyadev.phonebackup.shared.data.PhotoInbox
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.shared.data.UploadClient
import io.github.raghavsatyadev.phonebackup.shared.data.authedImageClient
import io.github.raghavsatyadev.phonebackup.shared.data.createHttpClient

/** Hand-wired app dependencies; one instance lives in the platform Application. */
class AppGraph(val settings: SettingsStore) {
  private val http = createHttpClient()
  val inbox = PhotoInbox()
  val uploads = UploadClient(http)
  val gallery = GalleryClient(http)
  /** Used by the image loader for gallery thumbnails; sends the token in a header. */
  val imageHttp by lazy { authedImageClient(settings) }
}
