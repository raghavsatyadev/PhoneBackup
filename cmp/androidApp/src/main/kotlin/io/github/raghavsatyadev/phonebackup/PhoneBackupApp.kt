package io.github.raghavsatyadev.phonebackup

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import io.github.raghavsatyadev.phonebackup.shared.AppGraph
import io.github.raghavsatyadev.phonebackup.shared.data.AndroidPhotoImporter
import io.github.raghavsatyadev.phonebackup.shared.data.AndroidSettingsStore
import io.github.raghavsatyadev.phonebackup.transfer.TransferNotifications
import io.github.raghavsatyadev.phonebackup.transfer.TransferService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PhoneBackupApp : Application() {
  lateinit var graph: AppGraph
    private set

  private lateinit var importer: AndroidPhotoImporter
  private var startedActivities = 0

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(AndroidSettingsStore(this))
    importer = AndroidPhotoImporter(this)
    TransferNotifications.createChannels(this)
    registerActivityLifecycleCallbacks(VisibilityTracker())
    val queue = graph.queue
    // Restoring isn't a transfer: no progress, so no service.
    graph.scope.launch { queue.add(importer.restore()) }
    // Any copy or upload (always started by the user while the app is open) gets the
    // foreground service and its progress notification.
    graph.scope.launch {
      queue.progress.map { it != null }.distinctUntilChanged().collect { busy ->
        if (busy) TransferService.start(this@PhoneBackupApp)
      }
    }
    // The screen shows its own message; the notification is for when the app is closed.
    graph.scope.launch {
      queue.summaries.collect { if (startedActivities == 0) TransferNotifications.postSummary(this@PhoneBackupApp, it) }
    }
  }

  fun importPhotos(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val queue = graph.queue
    graph.scope.launch { queue.add(queue.copying(uris.size) { report -> importer.import(uris, report) }) }
  }

  private inner class VisibilityTracker : ActivityLifecycleCallbacks {
    override fun onActivityStarted(activity: Activity) {
      startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
      startedActivities--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
  }
}
