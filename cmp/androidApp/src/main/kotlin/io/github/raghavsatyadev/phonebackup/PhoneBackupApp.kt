package io.github.raghavsatyadev.phonebackup

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.net.nsd.NsdManager
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import io.github.raghavsatyadev.phonebackup.discovery.LocalNetworkAccess
import io.github.raghavsatyadev.phonebackup.discovery.NsdDiscovery
import io.github.raghavsatyadev.phonebackup.shared.appModule
import io.github.raghavsatyadev.phonebackup.shared.data.ShareQueue
import kotlinx.coroutines.CoroutineScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import io.github.raghavsatyadev.phonebackup.shared.data.AndroidPhotoImporter
import io.github.raghavsatyadev.phonebackup.shared.data.AndroidKeyValueStore
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.transfer.TransferNotifications
import io.github.raghavsatyadev.phonebackup.transfer.TransferService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PhoneBackupApp : Application(), KoinComponent {
  val queue: ShareQueue by inject()
  private val scope: CoroutineScope by inject()

  lateinit var localNetwork: LocalNetworkAccess
    private set

  private lateinit var importer: AndroidPhotoImporter
  private var startedActivities = 0

  override fun onCreate() {
    super.onCreate()
    localNetwork = LocalNetworkAccess(this)
    startKoin {
      modules(
        appModule(
          settings = SettingsStore(AndroidKeyValueStore(this@PhoneBackupApp)),
          discovery = NsdDiscovery(getSystemService(NsdManager::class.java), localNetwork),
          deviceName = deviceName(),
        )
      )
    }
    importer = AndroidPhotoImporter(this)
    TransferNotifications.createChannels(this)
    registerActivityLifecycleCallbacks(VisibilityTracker())
    // Restoring isn't a transfer: no progress, so no service.
    scope.launch { queue.add(importer.restore()) }
    // Any copy or upload (always started by the user while the app is open) gets the
    // foreground service and its progress notification.
    scope.launch {
      queue.progress.map { it != null }.distinctUntilChanged().collect { busy ->
        if (busy) TransferService.start(this@PhoneBackupApp)
      }
    }
    // The screen shows its own message; the notification is for when the app is closed.
    scope.launch {
      queue.summaries.collect { if (startedActivities == 0) TransferNotifications.postSummary(this@PhoneBackupApp, it) }
    }
  }

  /** The name the user gave the phone (shown on the PC when pairing), else the model. */
  private fun deviceName(): String =
    Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

  fun importPhotos(uris: List<Uri>) {
    if (uris.isEmpty()) return
    scope.launch { queue.add(queue.copying(uris.size) { report -> importer.import(uris, report) }) }
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
