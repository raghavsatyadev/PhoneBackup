package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.compose.runtime.Immutable
import io.github.raghavsatyadev.phonebackup.shared.backup.data.AdbDevice
import io.github.raghavsatyadev.phonebackup.shared.backup.data.Transfer
import io.github.raghavsatyadev.phonebackup.shared.resources.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.StringResource

@Immutable
data class BackupUiState(
  /** False while the backup engine on this PC can't be reached. */
  val connected: Boolean = false,
  val devices: ImmutableList<AdbDevice> = persistentListOf(),
  val devicesLoaded: Boolean = false,
  val selectedSerial: String? = null,
  /** Folders on the phone to copy. */
  val sources: ImmutableList<String> = persistentListOf(),
  val pathDraft: String = "",
  /** Comma-separated name patterns; empty copies everything. */
  val filter: String = "",
  val destination: String = "",
  val destinationError: String? = null,
  val checkingDestination: Boolean = false,
  /** A backup already saved in the destination folder, which can be resumed. */
  val detected: Transfer? = null,
  /** Newest first. */
  val transfers: ImmutableList<Transfer> = persistentListOf(),
  val starting: Boolean = false,
  val wifiOpen: Boolean = false,
  val wifiAddress: String = "",
  val wifiBusy: Boolean = false,
  val wifiError: String? = null,
  val mismatch: DeviceMismatch? = null,
) {
  val selectedDevice: AdbDevice? get() = devices.firstOrNull { it.serial == selectedSerial }
  val canStart: Boolean
    get() = connected && selectedSerial != null && sources.isNotEmpty() && destination.isNotBlank() && !starting
}

/** Resuming a backup that was made from another phone than the one selected. */
@Immutable
data class DeviceMismatch(val transfer: Transfer, val oldLabel: String, val newSerial: String, val newLabel: String)

/** Ready-made name filters. */
enum class FilterPreset(val label: StringResource, val patterns: String) {
  All(Res.string.backup_filter_all, ""),
  Photos(Res.string.backup_filter_photos, "*.jpg, *.jpeg, *.png, *.heic, *.heif, *.webp, *.raw, *.dng"),
  Videos(Res.string.backup_filter_videos, "*.mp4, *.mkv, *.avi, *.mov, *.3gp, *.webm"),
  Docs(Res.string.backup_filter_docs, "*.pdf, *.doc, *.docx, *.xls, *.xlsx, *.ppt, *.pptx, *.txt, *.csv"),
  Music(Res.string.backup_filter_music, "*.mp3, *.flac, *.aac, *.ogg, *.wav, *.m4a"),
}

/** Common phone folders, one click to add. */
enum class QuickSource(val label: StringResource, val path: String) {
  Dcim(Res.string.backup_quick_dcim, "/storage/emulated/0/DCIM"),
  Pictures(Res.string.backup_quick_pictures, "/storage/emulated/0/Pictures"),
  WhatsApp(Res.string.backup_quick_whatsapp, "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media"),
  Movies(Res.string.backup_quick_movies, "/storage/emulated/0/Movies"),
  Music(Res.string.backup_quick_music, "/storage/emulated/0/Music"),
  Download(Res.string.backup_quick_download, "/storage/emulated/0/Download"),
  Documents(Res.string.backup_quick_documents, "/storage/emulated/0/Documents"),
  Recordings(Res.string.backup_quick_recordings, "/storage/emulated/0/Recordings"),
}

sealed interface BackupEvent {
  data object RefreshDevices : BackupEvent

  data class SelectDevice(val serial: String) : BackupEvent

  data object OpenWifi : BackupEvent

  data object CloseWifi : BackupEvent

  data class WifiAddress(val text: String) : BackupEvent

  data object ConnectWifi : BackupEvent

  data class DisconnectWifi(val serial: String) : BackupEvent

  data class AddSources(val paths: List<String>) : BackupEvent

  data class RemoveSource(val path: String) : BackupEvent

  data object ClearSources : BackupEvent

  data class PathDraft(val text: String) : BackupEvent

  /** Adds the typed path(s); commas or new lines separate several. */
  data object AddDraft : BackupEvent

  data class Filter(val text: String) : BackupEvent

  data class Destination(val text: String) : BackupEvent

  /** Checks the typed folder (creating it if needed) and looks for a saved backup there. */
  data object CheckDestination : BackupEvent

  data object BrowseDestination : BackupEvent

  data object Start : BackupEvent

  data class Pause(val id: String) : BackupEvent

  data class Resume(val transfer: Transfer) : BackupEvent

  data object ConfirmMismatch : BackupEvent

  data object DismissMismatch : BackupEvent

  data class Cancel(val id: String) : BackupEvent

  data class RetryFailed(val transfer: Transfer) : BackupEvent

  /** Picks a `phonebackup_status.json` and loads it as a paused backup. */
  data object ImportStatusFile : BackupEvent
}

/** A one-off note for the snackbar; [detail] fills `%1$s` (often the server's error text). */
data class BackupMessage(val text: StringResource, val detail: String = "")
