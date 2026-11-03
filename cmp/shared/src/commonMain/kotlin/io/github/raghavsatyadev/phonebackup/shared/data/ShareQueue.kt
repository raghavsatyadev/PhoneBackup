package io.github.raghavsatyadev.phonebackup.shared.data

import androidx.compose.runtime.Immutable
import io.github.raghavsatyadev.phonebackup.shared.model.IncomingPhoto
import io.github.raghavsatyadev.phonebackup.shared.model.PhotoItem
import io.github.raghavsatyadev.phonebackup.shared.model.UploadStatus
import io.github.raghavsatyadev.phonebackup.shared.model.isFinished
import io.github.raghavsatyadev.phonebackup.shared.model.needsUpload
import io.github.raghavsatyadev.phonebackup.shared.model.toItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sends one photo; returns true when the server already had it. Tests pass a fake. */
fun interface PhotoSender {
  suspend fun send(
    config: ServerConfig,
    photo: PhotoItem,
    album: String,
    tags: List<String>,
    onProgress: (Float) -> Unit,
  ): Boolean
}

enum class TransferPhase {
  Copying,
  Uploading,
}

/** What the app is busy with right now, for the screen and the progress notification. */
@Immutable
data class TransferProgress(
  val phase: TransferPhase,
  val done: Int,
  val total: Int,
  val fileName: String = "",
  /** 0..1 for the file being sent right now. */
  val fileFraction: Float = 0f,
) {
  val fraction: Float
    get() = if (total <= 0) 0f else ((done + fileFraction) / total).coerceIn(0f, 1f)
}

data class UploadSummary(val sent: Int, val failed: Int, val cancelled: Boolean)

/**
 * The photos waiting to go to the PC and the upload that sends them. Lives on an app-wide
 * scope (not the screen's), so leaving the app doesn't stop an upload; on Android a
 * foreground service keeps the process alive while [progress] is not null.
 */
class ShareQueue(
  private val scope: CoroutineScope,
  private val sender: PhotoSender,
  /** Called when nothing is left to upload (the share draft is cleared then). */
  private val onAllSent: () -> Unit = {},
) {
  private val _photos = MutableStateFlow<ImmutableList<PhotoItem>>(persistentListOf())
  val photos: StateFlow<ImmutableList<PhotoItem>> = _photos.asStateFlow()

  private val _upload = MutableStateFlow<TransferProgress?>(null)
  /** Upload progress only; null when no upload runs. */
  val uploadProgress: StateFlow<TransferProgress?> = _upload.asStateFlow()

  private val _copy = MutableStateFlow<TransferProgress?>(null)
  /** Copy progress only; null when nothing is being copied in. */
  val copyProgress: StateFlow<TransferProgress?> = _copy.asStateFlow()
  private var copiesRunning = 0
  private val copyLock = Mutex()

  /** Upload first, else copying, else null (idle). */
  val progress: StateFlow<TransferProgress?> =
    combine(_upload, _copy) { u, c -> u ?: c }.stateIn(scope, SharingStarted.Eagerly, null)

  private val _deliveries = MutableStateFlow(0)
  /** Goes up by one each time photos arrive, so the UI can switch to the share screen. */
  val deliveries: StateFlow<Int> = _deliveries.asStateFlow()

  private val _summaries = MutableSharedFlow<UploadSummary>(extraBufferCapacity = 4)
  val summaries: SharedFlow<UploadSummary> = _summaries

  private var uploadJob: Job? = null

  fun add(incoming: List<IncomingPhoto>) {
    if (incoming.isEmpty()) return
    _photos.update { list -> (list + incoming.map { it.toItem() }).distinctBy { it.id }.toImmutableList() }
    _deliveries.update { it + 1 }
  }

  /**
   * Runs a copy of [total] shared files into app storage while showing progress.
   * [block] reports (filesDone, name of the file just copied).
   */
  suspend fun <T> copying(total: Int, block: suspend (report: (Int, String) -> Unit) -> T): T {
    val base =
      copyLock.withLock {
        copiesRunning++
        val before = _copy.value
        _copy.value = TransferProgress(TransferPhase.Copying, before?.done ?: 0, (before?.total ?: 0) + total)
        before?.total ?: 0
      }
    try {
      return block { done, name ->
        _copy.update { it?.copy(done = base + done, fileName = name) }
      }
    } finally {
      withContext(NonCancellable) { copyLock.withLock { if (--copiesRunning == 0) _copy.value = null } }
    }
  }

  fun remove(id: String) {
    val photo = _photos.value.firstOrNull { it.id == id } ?: return
    if (photo.status == UploadStatus.Uploading) return
    deleteImported(photo.path)
    _photos.update { list -> list.filterNot { it.id == id }.toImmutableList() }
    clearDraftIfDone()
  }

  /** Changes every photo that hasn't been sent yet and matches [where]. */
  fun updateWaiting(where: (PhotoItem) -> Boolean, change: (PhotoItem) -> PhotoItem) {
    _photos.update { list -> list.map { if (!it.isFinished && where(it)) change(it) else it }.toImmutableList() }
  }

  fun clearFinished() {
    _photos.value.filter { it.isFinished }.forEach { deleteImported(it.path) }
    _photos.update { list -> list.filterNot { it.isFinished }.toImmutableList() }
    clearDraftIfDone()
  }

  val isUploading: Boolean
    get() = uploadJob?.isActive == true

  /** Starts sending every waiting photo; false when one is already running or nothing waits. */
  fun upload(config: ServerConfig, album: String, tags: List<String>): Boolean {
    if (isUploading) return false
    val todo = _photos.value.filter { it.needsUpload }.map { it.id }
    if (todo.isEmpty()) return false
    _upload.value = TransferProgress(TransferPhase.Uploading, 0, todo.size)
    uploadJob = scope.launch { runUpload(config, album, tags, todo) }
    return true
  }

  fun cancel() {
    uploadJob?.cancel()
  }

  private suspend fun runUpload(config: ServerConfig, album: String, tags: List<String>, todo: List<String>) {
    var sent = 0
    var failed = 0
    var cancelled = false
    try {
      todo.forEachIndexed { index, id ->
        // The user may have removed it while earlier photos were uploading.
        val photo = _photos.value.firstOrNull { it.id == id && it.needsUpload } ?: return@forEachIndexed
        setPhoto(id) { it.copy(status = UploadStatus.Uploading, error = null) }
        _upload.value = TransferProgress(TransferPhase.Uploading, index, todo.size, photo.fileName)
        try {
          val skipped =
            sender.send(config, photo, album, tags) { f ->
              _upload.update { it?.copy(fileFraction = f.coerceIn(0f, 1f)) }
            }
          markImportedSent(photo.path)
          setPhoto(id) { it.copy(status = if (skipped) UploadStatus.Skipped else UploadStatus.Done) }
          sent++
        } catch (e: CancellationException) {
          // Cancelled by the user: this photo simply waits for the next upload.
          setPhoto(id) { it.copy(status = UploadStatus.Pending) }
          throw e
        } catch (e: Exception) {
          failed++
          setPhoto(id) { it.copy(status = UploadStatus.Failed, error = e.message ?: e.toString()) }
        }
      }
    } catch (e: CancellationException) {
      cancelled = true
      throw e
    } finally {
      withContext(NonCancellable) {
        _upload.value = null
        clearDraftIfDone()
        _summaries.emit(UploadSummary(sent, failed, cancelled))
      }
    }
  }

  private fun clearDraftIfDone() {
    if (_photos.value.none { it.needsUpload }) onAllSent()
  }

  private fun setPhoto(id: String, change: (PhotoItem) -> PhotoItem) {
    _photos.update { list -> list.map { if (it.id == id) change(it) else it }.toImmutableList() }
  }
}
