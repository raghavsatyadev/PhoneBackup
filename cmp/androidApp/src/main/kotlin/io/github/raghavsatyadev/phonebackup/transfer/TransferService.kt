package io.github.raghavsatyadev.phonebackup.transfer

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.raghavsatyadev.phonebackup.PhoneBackupApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground while photos are being copied in or uploaded, so Android
 * doesn't kill the work when the user leaves the app. The work itself runs in ShareQueue;
 * this only mirrors its progress into the notification and stops when it goes idle.
 */
class TransferService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var watcher: Job? = null
  private val queue
    get() = (application as PhoneBackupApp).queue

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL) queue.cancel()
    // Must go foreground on every start, even one that only cancels.
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
    ServiceCompat.startForeground(
      this,
      TransferNotifications.PROGRESS_ID,
      TransferNotifications.progress(this, queue.progress.value),
      type,
    )
    if (watcher == null) watcher = scope.launch { watch() }
    return START_NOT_STICKY
  }

  private suspend fun watch() {
    val nm = NotificationManagerCompat.from(this)
    queue.progress.collect { p ->
      if (p == null) {
        stop()
        return@collect
      }
      try {
        nm.notify(TransferNotifications.PROGRESS_ID, TransferNotifications.progress(this, p))
      } catch (_: SecurityException) {
        // No notification permission: the work still runs, just without a visible bar.
      }
      // StateFlow is already conflated: while we wait, only the newest value is kept.
    // Android drops notification updates sent faster than a few per second.
      delay(UPDATE_EVERY_MS)
    }
  }

  private fun stop() {
    watcher?.cancel()
    watcher = null
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  /** Android 15+ limits dataSync services to 6 hours a day; stop cleanly when told to. */
  override fun onTimeout(startId: Int, fgsType: Int) {
    queue.cancel()
    stop()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "TransferService"
    private const val ACTION_CANCEL = "io.github.raghavsatyadev.phonebackup.CANCEL_UPLOAD"
    private const val UPDATE_EVERY_MS = 500L

    /** Call while the app is in the foreground (a share, the picker, or the Upload button). */
    fun start(context: Context) {
      try {
        ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
      } catch (e: IllegalStateException) {
        // Started from the background (ForegroundServiceStartNotAllowedException on 12+):
        // the work still runs while the process lives.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
          Log.w(TAG, "Could not start the progress service", e)
        } else {
          throw e
        }
      }
    }

    fun cancelIntent(context: Context): PendingIntent =
      PendingIntent.getService(
        context,
        1,
        Intent(context, TransferService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
  }
}
