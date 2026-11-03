package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.data.PhotoInbox
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.shared.data.ShareDraft
import io.github.raghavsatyadev.phonebackup.shared.data.UploadClient
import io.github.raghavsatyadev.phonebackup.shared.data.deleteImported
import io.github.raghavsatyadev.phonebackup.shared.data.saveImportedDate
import io.github.raghavsatyadev.phonebackup.shared.model.DateSource
import io.github.raghavsatyadev.phonebackup.shared.model.PhotoItem
import io.github.raghavsatyadev.phonebackup.shared.model.UploadStatus
import io.github.raghavsatyadev.phonebackup.shared.model.isFinished
import io.github.raghavsatyadev.phonebackup.shared.model.needsUpload
import io.github.raghavsatyadev.phonebackup.shared.model.toItem
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.msg_upload_done
import io.github.raghavsatyadev.phonebackup.shared.resources.msg_upload_partial
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ShareViewModel(
  private val inbox: PhotoInbox,
  private val settings: SettingsStore,
  private val uploads: UploadClient,
) : ViewModel() {
  private val _state =
    MutableStateFlow(settings.loadDraft().let { ShareUiState(album = it.album, tags = it.tags.toImmutableList()) })
  val state: StateFlow<ShareUiState> = _state.asStateFlow()

  private val _messages = Channel<ShareMessage>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  init {
    viewModelScope.launch {
      inbox.pending.filter { it.isNotEmpty() }.collect {
        val incoming = inbox.drain().map { p -> p.toItem() }
        _state.update { s -> s.copy(photos = (s.photos + incoming).distinctBy { it.id }.toImmutableList()) }
        // The album and tags still on screen now belong to these photos too.
        saveDraft()
      }
    }
    viewModelScope.launch {
      settings.config.collect { c -> _state.update { it.copy(serverConfigured = c.isComplete) } }
    }
  }

  fun onEvent(event: ShareEvent) {
    when (event) {
      is ShareEvent.AlbumChanged -> {
        _state.update { it.copy(album = event.value) }
        saveDraft()
      }
      is ShareEvent.TagInputChanged -> onTagInput(event.value)
      ShareEvent.AddTag -> addTags(_state.value.tagInput)
      is ShareEvent.RemoveTag -> {
        _state.update { s -> s.copy(tags = s.tags.filterNot { it == event.tag }.toImmutableList()) }
        saveDraft()
      }
      is ShareEvent.RemovePhoto -> removePhoto(event.id)
      is ShareEvent.EditDate -> _state.update { it.copy(dateTarget = event.target) }
      ShareEvent.DismissDateEdit -> _state.update { it.copy(dateTarget = null) }
      is ShareEvent.DateChosen -> applyDate(event)
      ShareEvent.Upload -> upload()
      ShareEvent.ClearFinished -> {
        _state.update { s -> s.copy(photos = s.photos.filterNot { it.isFinished }.toImmutableList()) }
        clearDraftIfDone()
      }
    }
  }

  private fun saveDraft() {
    val s = _state.value
    settings.saveDraft(ShareDraft(s.album, s.tags))
  }

  /** Once nothing is waiting, the next share starts with an empty album and tags. */
  private fun clearDraftIfDone() {
    if (_state.value.photos.none { it.needsUpload }) settings.saveDraft(ShareDraft())
  }

  /** Typing a comma finishes a tag, like most tag inputs. */
  private fun onTagInput(value: String) {
    if (value.contains(',')) {
      // Keep whatever follows the last comma: the user is still typing it.
      addTags(value.substringBeforeLast(','), keep = value.substringAfterLast(','))
    } else {
      _state.update { it.copy(tagInput = value) }
    }
  }

  private fun addTags(raw: String, keep: String = "") {
    val fresh = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    _state.update { s ->
      val merged = (s.tags + fresh).distinctBy { it.lowercase() }
      s.copy(tags = merged.toImmutableList(), tagInput = keep.trimStart())
    }
    saveDraft()
  }

  private fun removePhoto(id: String) {
    val photo = _state.value.photos.firstOrNull { it.id == id } ?: return
    if (photo.status == UploadStatus.Uploading) return
    deleteImported(photo.path)
    _state.update { s -> s.copy(photos = s.photos.filterNot { it.id == id }.toImmutableList()) }
    clearDraftIfDone()
  }

  private fun applyDate(event: ShareEvent.DateChosen) {
    val target = _state.value.dateTarget ?: return
    _state.update { s ->
      val photos =
        s.photos.map { p ->
          val hit = target == DateTarget.All || (target is DateTarget.One && target.id == p.id)
          if (hit && !p.isFinished) {
            saveImportedDate(p.path, event.value)
            p.copy(takenAt = event.value, dateSource = DateSource.Manual)
          } else p
        }
      s.copy(photos = photos.toImmutableList(), dateTarget = null)
    }
  }

  private fun upload() {
    if (!_state.value.canUpload) return
    // Commit a half-typed tag so it isn't silently dropped.
    if (_state.value.tagInput.isNotBlank()) addTags(_state.value.tagInput)
    _state.update { it.copy(isUploading = true, currentProgress = 0f) }
    viewModelScope.launch {
      val config = settings.config.value
      val album = _state.value.album.trim()
      val tags = _state.value.tags.toList()
      var sent = 0
      var failed = 0
      for (photo in _state.value.photos.filter { it.needsUpload }) {
        // The user may have removed it while earlier photos were uploading.
        val current = _state.value.photos.firstOrNull { it.id == photo.id } ?: continue
        setPhoto(current.id) { it.copy(status = UploadStatus.Uploading, error = null) }
        _state.update { it.copy(currentProgress = 0f) }
        try {
          val res =
            uploads.upload(
              config = config,
              path = current.path,
              fileName = current.fileName,
              sizeBytes = current.sizeBytes,
              album = album,
              tags = tags,
              takenAt = current.takenAt,
            ) { done, total ->
              if (total > 0) _state.update { it.copy(currentProgress = done.toFloat() / total) }
            }
          deleteImported(current.path)
          val status = if (res.skipped) UploadStatus.Skipped else UploadStatus.Done
          setPhoto(current.id) { it.copy(status = status) }
          sent++
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          failed++
          setPhoto(current.id) { it.copy(status = UploadStatus.Failed, error = e.message ?: e.toString()) }
        }
      }
      _state.update { it.copy(isUploading = false, currentProgress = 0f) }
      clearDraftIfDone()
      val message =
        if (failed == 0) ShareMessage(Res.string.msg_upload_done, sent.toString())
        else ShareMessage(Res.string.msg_upload_partial, failed.toString())
      _messages.send(message)
    }
  }

  private fun setPhoto(id: String, change: (PhotoItem) -> PhotoItem) {
    _state.update { s -> s.copy(photos = s.photos.map { if (it.id == id) change(it) else it }.toImmutableList()) }
  }
}
