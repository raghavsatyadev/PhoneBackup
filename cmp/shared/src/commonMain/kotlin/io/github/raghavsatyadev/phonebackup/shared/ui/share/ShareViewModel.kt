package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.shared.data.ShareDraft
import io.github.raghavsatyadev.phonebackup.shared.data.ShareQueue
import io.github.raghavsatyadev.phonebackup.shared.data.saveImportedDate
import io.github.raghavsatyadev.phonebackup.shared.model.DateSource
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.msg_upload_cancelled
import io.github.raghavsatyadev.phonebackup.shared.resources.msg_upload_done
import io.github.raghavsatyadev.phonebackup.shared.resources.msg_upload_partial
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The share form (album, tags, date edits). The photo list and the upload itself live in
 * [ShareQueue], which outlives this screen.
 */
class ShareViewModel(
  private val queue: ShareQueue,
  private val settings: SettingsStore,
) : ViewModel() {
  private val form =
    MutableStateFlow(settings.loadDraft().let { ShareUiState(album = it.album, tags = it.tags.toImmutableList()) })

  val state: StateFlow<ShareUiState> =
    combine(form, queue.photos, queue.uploadProgress, queue.copyProgress, settings.servers) { f, photos, upload, copy, list ->
        val active = list.active
        f.copy(
          photos = photos,
          copying = copy,
          isUploading = upload != null,
          currentProgress = upload?.fileFraction ?: 0f,
          serverConfigured = active?.config?.isComplete == true,
          pcs = list.servers.filter { it.config.isComplete }.map { PcChoice(it.localId, it.label) }.toImmutableList(),
          activePc = active?.localId,
        )
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, form.value.copy(photos = queue.photos.value))

  private val _messages = Channel<ShareMessage>(Channel.BUFFERED)
  val messages = _messages.receiveAsFlow()

  init {
    // The album and tags still on screen now belong to newly arrived photos too.
    viewModelScope.launch { queue.deliveries.drop(1).collect { saveDraft() } }
    viewModelScope.launch {
      queue.summaries.collect { s ->
        val message =
          when {
            s.cancelled -> ShareMessage(Res.string.msg_upload_cancelled, s.sent.toString())
            s.failed == 0 -> ShareMessage(Res.string.msg_upload_done, s.sent.toString())
            else -> ShareMessage(Res.string.msg_upload_partial, s.failed.toString())
          }
        _messages.send(message)
      }
    }
  }

  fun onEvent(event: ShareEvent) {
    when (event) {
      is ShareEvent.AlbumChanged -> {
        form.update { it.copy(album = event.value) }
        saveDraft()
      }
      is ShareEvent.TagInputChanged -> onTagInput(event.value)
      ShareEvent.AddTag -> addTags(form.value.tagInput)
      is ShareEvent.RemoveTag -> {
        form.update { s -> s.copy(tags = s.tags.filterNot { it == event.tag }.toImmutableList()) }
        saveDraft()
      }
      is ShareEvent.RemovePhoto -> queue.remove(event.id)
      is ShareEvent.EditDate -> form.update { it.copy(dateTarget = event.target) }
      ShareEvent.DismissDateEdit -> form.update { it.copy(dateTarget = null) }
      is ShareEvent.DateChosen -> applyDate(event)
      ShareEvent.Upload -> upload()
      is ShareEvent.SelectPc -> if (!state.value.isUploading) settings.update { it.activate(event.localId) }
      ShareEvent.CancelUpload -> queue.cancel()
      ShareEvent.ClearFinished -> queue.clearFinished()
    }
  }

  private fun saveDraft() {
    val s = form.value
    settings.saveDraft(ShareDraft(s.album, s.tags))
  }

  /** Typing a comma finishes a tag, like most tag inputs. */
  private fun onTagInput(value: String) {
    if (value.contains(',')) {
      // Keep whatever follows the last comma: the user is still typing it.
      addTags(value.substringBeforeLast(','), keep = value.substringAfterLast(','))
    } else {
      form.update { it.copy(tagInput = value) }
    }
  }

  private fun addTags(raw: String, keep: String = "") {
    val fresh = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    form.update { s ->
      val merged = (s.tags + fresh).distinctBy { it.lowercase() }
      s.copy(tags = merged.toImmutableList(), tagInput = keep.trimStart())
    }
    saveDraft()
  }

  private fun applyDate(event: ShareEvent.DateChosen) {
    val target = form.value.dateTarget ?: return
    queue.updateWaiting(
      where = { target == DateTarget.All || (target is DateTarget.One && target.id == it.id) },
    ) { p ->
      saveImportedDate(p.path, event.value)
      p.copy(takenAt = event.value, dateSource = DateSource.Manual)
    }
    form.update { it.copy(dateTarget = null) }
  }

  private fun upload() {
    if (!state.value.canUpload) return
    // Commit a half-typed tag so it isn't silently dropped.
    if (form.value.tagInput.isNotBlank()) addTags(form.value.tagInput)
    val s = form.value
    queue.upload(settings.config.value, s.album.trim(), s.tags.toList())
  }
}
