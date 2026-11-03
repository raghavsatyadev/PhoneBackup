package io.github.raghavsatyadev.phonebackup.shared.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.shared.data.UploadClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface FolderMessage {
  data class Changed(val path: String) : FolderMessage

  data class Failed(val reason: String) : FolderMessage
}

@Immutable
data class FolderUiState(
  /** Where the PC saves photos; null until the PC answered. */
  val current: String? = null,
  /** True only when this app runs on that PC (the PC decides). */
  val canChange: Boolean = false,
  val draft: String = "",
  val busy: Boolean = false,
  val message: FolderMessage? = null,
)

sealed interface FolderEvent {
  data class DraftChanged(val value: String) : FolderEvent

  data object Browse : FolderEvent

  data object Apply : FolderEvent

  data object Refresh : FolderEvent
}

/** The PC's photo folder, for the desktop app only (it supplies [pickFolder]). */
class FolderViewModel(
  private val settings: SettingsStore,
  private val uploads: UploadClient,
  private val localId: String?,
  private val pickFolder: suspend (String) -> String?,
) : ViewModel() {
  private val _state = MutableStateFlow(FolderUiState())
  val state: StateFlow<FolderUiState> = _state.asStateFlow()

  init {
    refresh()
  }

  fun onEvent(event: FolderEvent) {
    when (event) {
      is FolderEvent.DraftChanged -> _state.update { it.copy(draft = event.value, message = null) }
      FolderEvent.Browse ->
        viewModelScope.launch {
          val picked = pickFolder(_state.value.draft.ifBlank { _state.value.current.orEmpty() })
          if (picked != null) _state.update { it.copy(draft = picked, message = null) }
        }
      FolderEvent.Apply -> apply()
      FolderEvent.Refresh -> refresh()
    }
  }

  private fun config() = settings.servers.value.find(localId)?.config?.takeIf { it.isComplete }

  private fun refresh() {
    val config = config() ?: return
    viewModelScope.launch {
      runCatching { uploads.folder(config) }
        .onSuccess { info -> _state.update { it.copy(current = info.path, canChange = info.canChange, draft = it.draft.ifBlank { info.path }) } }
        .onFailure { if (it is CancellationException) throw it }
    }
  }

  private fun apply() {
    val config = config() ?: return
    val path = _state.value.draft.trim()
    if (path.isBlank()) return
    _state.update { it.copy(busy = true, message = null) }
    viewModelScope.launch {
      val next =
        try {
          val info = uploads.setFolder(config, path)
          _state.value.copy(current = info.path, draft = info.path, message = FolderMessage.Changed(info.path))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          _state.value.copy(message = FolderMessage.Failed(e.message ?: e.toString()))
        }
      _state.value = next.copy(busy = false)
    }
  }
}
