package io.github.raghavsatyadev.phonebackup

import android.app.Application
import android.net.Uri
import io.github.raghavsatyadev.phonebackup.shared.AppGraph
import io.github.raghavsatyadev.phonebackup.shared.data.AndroidPhotoImporter
import io.github.raghavsatyadev.phonebackup.shared.data.AndroidSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoneBackupApp : Application() {
  lateinit var graph: AppGraph
    private set

  private lateinit var importer: AndroidPhotoImporter
  // App-wide scope so a copy in progress survives the activity being recreated.
  private val scope = CoroutineScope(SupervisorJob())

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(AndroidSettingsStore(this))
    importer = AndroidPhotoImporter(this)
    scope.launch { graph.inbox.deliver(importer.restore()) }
  }

  fun importPhotos(uris: List<Uri>) {
    if (uris.isEmpty()) return
    scope.launch { graph.inbox.deliver(importer.import(uris)) }
  }
}
