package io.github.raghavsatyadev.phonebackup.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.raghavsatyadev.phonebackup.MainActivity
import io.github.raghavsatyadev.phonebackup.R
import io.github.raghavsatyadev.phonebackup.shared.data.TransferPhase
import io.github.raghavsatyadev.phonebackup.shared.data.TransferProgress
import io.github.raghavsatyadev.phonebackup.shared.data.UploadSummary

/**
 * Progress notifications in the Android 16 progress-centric style (ProgressStyle), asking
 * to be promoted to a Live Update (status-bar chip, top of the shade, lock screen).
 * Older Android versions get a normal progress bar from NotificationCompat.
 */
object TransferNotifications {
  const val PROGRESS_ID = 1
  private const val DONE_ID = 2
  private const val CHANNEL_PROGRESS = "transfer"
  private const val CHANNEL_DONE = "transfer_done"
  /** Progress track length; a single segment so any photo count fits. */
  private const val TRACK = 1000

  fun createChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(NotificationManager::class.java)
    // Live Updates are refused on IMPORTANCE_MIN channels; LOW stays silent.
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_transfer), NotificationManager.IMPORTANCE_LOW)
    )
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_DONE, context.getString(R.string.channel_transfer_done), NotificationManager.IMPORTANCE_DEFAULT)
    )
  }

  fun progress(context: Context, p: TransferProgress?): Notification {
    val uploading = p?.phase == TransferPhase.Uploading
    val title = context.getString(if (uploading) R.string.notif_uploading else R.string.notif_copying)
    val builder =
      NotificationCompat.Builder(context, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_stat_upload)
        .setContentTitle(title)
        .setContentIntent(openApp(context))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setRequestPromotedOngoing(true)
    val style =
      NotificationCompat.ProgressStyle()
        .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(TRACK)))
        .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_upload))
    if (p == null || p.total == 0) {
      builder.setContentText(context.getString(R.string.notif_preparing)).setStyle(style.setProgressIndeterminate(true))
      return builder.build()
    }
    // The photo being worked on counts as "n of total" while uploading; copying reports finished files.
    val shown = if (uploading) (p.done + 1).coerceAtMost(p.total) else p.done
    val count = context.getString(R.string.notif_count, shown, p.total)
    builder
      .setContentText(if (p.fileName.isBlank()) count else "$count · ${p.fileName}")
      .setShortCriticalText("$shown/${p.total}")
      .setStyle(style.setProgress((p.fraction * TRACK).toInt()))
    if (uploading) {
      builder.addAction(0, context.getString(R.string.notif_cancel), TransferService.cancelIntent(context))
    }
    return builder.build()
  }

  /** A plain, dismissible result once an upload ends (only posted when the app isn't open). */
  fun postSummary(context: Context, s: UploadSummary) {
    val (title, text) =
      when {
        s.cancelled -> R.string.notif_stopped_title to context.getString(R.string.notif_stopped_text, s.sent)
        s.failed > 0 -> R.string.notif_failed_title to context.getString(R.string.notif_failed_text, s.failed)
        else -> R.string.notif_done_title to context.getString(R.string.notif_done_text, s.sent)
      }
    val n =
      NotificationCompat.Builder(context, CHANNEL_DONE)
        .setSmallIcon(R.drawable.ic_stat_upload)
        .setContentTitle(context.getString(title))
        .setContentText(text)
        .setContentIntent(openApp(context))
        .setAutoCancel(true)
        .build()
    val nm = NotificationManagerCompat.from(context)
    if (nm.areNotificationsEnabled()) {
      try {
        nm.notify(DONE_ID, n)
      } catch (_: SecurityException) {
        // Permission revoked between the check and the post.
      }
    }
  }

  private fun openApp(context: Context): PendingIntent =
    PendingIntent.getActivity(
      context,
      0,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
