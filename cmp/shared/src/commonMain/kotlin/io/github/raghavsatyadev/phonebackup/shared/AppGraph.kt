package io.github.raghavsatyadev.phonebackup.shared

import io.github.raghavsatyadev.phonebackup.shared.data.PhotoInbox
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.shared.data.UploadClient
import io.github.raghavsatyadev.phonebackup.shared.data.createHttpClient

/** Hand-wired app dependencies; one instance lives in the platform Application. */
class AppGraph(val settings: SettingsStore) {
  val inbox = PhotoInbox()
  val uploads = UploadClient(createHttpClient())
}
