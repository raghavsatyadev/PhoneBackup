package io.github.raghavsatyadev.phonebackup.shared.backup.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * What the PhoneBackup server's backup API sends (server/server.go, adb.go, transfer.go).
 * Go writes an empty list as null, so every list here has a default and [BackupJson] turns null
 * into it.
 */

/** A phone ADB can see (`adb devices -l`); [transport] is "usb" or "wifi". */
@Immutable
@Serializable
data class AdbDevice(val serial: String, val state: String = "", val model: String = "", val transport: String = "") {
  val isWifi: Boolean get() = transport == "wifi"
  val label: String get() = model.ifBlank { serial }
}

/** One entry of a folder on the phone. */
@Immutable
@Serializable
data class RemoteEntry(val name: String, val path: String, val isDir: Boolean = false, val size: Long = 0)

/** One line of a transfer's activity log: done, skipped or failed. */
@Immutable
@Serializable
data class LogEntry(
  val time: String = "",
  val status: String = "",
  @SerialName("remote_path") val remotePath: String = "",
  val size: Long = 0,
  val error: String = "",
)

/** One file of a transfer's catalog (only failed ones are fetched). */
@Immutable
@Serializable
data class FileItem(
  @SerialName("remote_path") val remotePath: String = "",
  @SerialName("local_path") val localPath: String = "",
  val size: Long = 0,
  val status: String = "",
  val error: String = "",
)

@Immutable
@Serializable
data class BackupFilters(val include: List<String> = emptyList(), val exclude: List<String> = emptyList())

/**
 * A backup run, as the summary or a live update. The live update also carries the whole file
 * catalog (`files`), which is left out here on purpose.
 */
@Immutable
@Serializable
data class Transfer(
  val id: String,
  @SerialName("active_serial") val activeSerial: String = "",
  @SerialName("known_serials") val knownSerials: List<String> = emptyList(),
  @SerialName("device_model") val deviceModel: String = "",
  val sources: List<String> = emptyList(),
  @SerialName("local_dir") val localDir: String = "",
  val filters: BackupFilters = BackupFilters(),
  val status: String = "",
  @SerialName("total_files") val totalFiles: Int = 0,
  @SerialName("done_files") val doneFiles: Int = 0,
  @SerialName("skipped_files") val skippedFiles: Int = 0,
  @SerialName("failed_files") val failedFiles: Int = 0,
  @SerialName("total_bytes") val totalBytes: Long = 0,
  @SerialName("done_bytes") val doneBytes: Long = 0,
  @SerialName("created_at") val createdAt: String = "",
  @SerialName("updated_at") val updatedAt: String = "",
  val error: String = "",
  @SerialName("current_file") val currentFile: String = "",
  @SerialName("current_file_size") val currentFileSize: Long = 0,
  @SerialName("current_file_done") val currentFileDone: Long = 0,
  @SerialName("speed_bps") val speedBps: Double = 0.0,
  @SerialName("eta_seconds") val etaSeconds: Long = 0,
  @SerialName("recent_logs") val recentLogs: List<LogEntry> = emptyList(),
) {
  val isActive: Boolean get() = status == STATUS_RUNNING || status == STATUS_SCANNING || status == STATUS_PENDING
  val canResume: Boolean get() = status == STATUS_PAUSED || status == STATUS_FAILED || status == STATUS_CANCELLED
  val handledFiles: Int get() = doneFiles + skippedFiles + failedFiles

  /** 0..1 by bytes, or by files while sizes are unknown. */
  val progress: Float
    get() =
      when {
        totalBytes > 0 -> (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        totalFiles > 0 -> (handledFiles.toFloat() / totalFiles).coerceIn(0f, 1f)
        else -> 0f
      }

  /** True when [serial] is the phone this backup belongs to (it may have several serials: USB and Wi-Fi). */
  fun belongsTo(serial: String): Boolean = serial == activeSerial || serial in knownSerials

  companion object {
    const val STATUS_PENDING = "pending"
    const val STATUS_SCANNING = "scanning"
    const val STATUS_RUNNING = "running"
    const val STATUS_PAUSED = "paused"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"
  }
}

/** What `/api/local/set-destination` found at a folder. [transfer] is set when it holds a backup. */
@Serializable
data class DestinationInfo(
  val path: String = "",
  val created: Boolean = false,
  val hasStatus: Boolean = false,
  val transfer: Transfer? = null,
)

@Serializable internal data class ImportResult(val transfer: Transfer? = null)

@Serializable internal data class RetryResult(val retriedCount: Int = 0)

@Serializable internal data class FailedResult(val items: List<FileItem> = emptyList())

@Serializable internal data class ServerMessage(val message: String = "", val error: String = "")

/** A WebSocket message. Only `transfer_update` carries a [transfer]; pairing messages are ignored. */
@Serializable internal data class SocketMessage(val type: String = "", val transfer: Transfer? = null)

internal val BackupJson = Json {
  ignoreUnknownKeys = true
  coerceInputValues = true
  explicitNulls = false
}
