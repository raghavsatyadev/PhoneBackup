package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.compose.runtime.Composable
import io.github.raghavsatyadev.phonebackup.shared.backup.data.Transfer
import io.github.raghavsatyadev.phonebackup.shared.resources.*
import java.util.Locale
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** "1.4 GB", "820 KB". */
fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val units = listOf("KB", "MB", "GB", "TB")
  var value = bytes / 1024.0
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  return String.format(Locale.getDefault(), if (value < 10) "%.1f %s" else "%.0f %s", value, units[unit])
}

fun formatSpeed(bps: Double): String = formatBytes(bps.toLong()) + "/s"

/** "1:05:09" or "4:07". */
fun formatDuration(seconds: Long): String {
  val s = seconds.coerceAtLeast(0)
  val h = s / 3600
  val m = (s % 3600) / 60
  val sec = s % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** The part of a phone path after the last slash. */
fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/')

@Composable
fun statusLabel(status: String): String = stringResource(statusRes(status))

private fun statusRes(status: String): StringResource =
  when (status) {
    Transfer.STATUS_PENDING -> Res.string.backup_status_pending
    Transfer.STATUS_SCANNING -> Res.string.backup_status_scanning
    Transfer.STATUS_RUNNING -> Res.string.backup_status_running
    Transfer.STATUS_PAUSED -> Res.string.backup_status_paused
    Transfer.STATUS_COMPLETED -> Res.string.backup_status_completed
    Transfer.STATUS_FAILED -> Res.string.backup_status_failed
    Transfer.STATUS_CANCELLED -> Res.string.backup_status_cancelled
    else -> Res.string.backup_status_pending
  }
