package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.backup.data.BackupApi
import io.github.raghavsatyadev.phonebackup.shared.backup.data.RemoteEntry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class PhoneFoldersUiState(
  val path: String = ROOT,
  val entries: ImmutableList<RemoteEntry> = persistentListOf(),
  val loading: Boolean = true,
  val error: String? = null,
  /** Folders ticked so far, across every folder visited. */
  val checked: ImmutableSet<String> = persistentSetOf(),
) {
  val canGoUp: Boolean get() = path != ROOT && path.count { it == '/' } > 1

  companion object {
    const val ROOT = "/sdcard"
  }
}

sealed interface PhoneFoldersEvent {
  data class Open(val path: String) : PhoneFoldersEvent

  data object Up : PhoneFoldersEvent

  data object Reload : PhoneFoldersEvent

  data class Toggle(val path: String) : PhoneFoldersEvent
}

/** Browses the folders of one phone, so the user can tick which to back up. */
class PhoneFoldersViewModel(private val api: BackupApi, private val serial: String) : ViewModel() {
  private val _state = MutableStateFlow(PhoneFoldersUiState())
  val state: StateFlow<PhoneFoldersUiState> = _state.asStateFlow()
  private var loadJob: Job? = null

  init {
    load(PhoneFoldersUiState.ROOT)
  }

  fun onEvent(event: PhoneFoldersEvent) {
    when (event) {
      is PhoneFoldersEvent.Open -> load(event.path)
      PhoneFoldersEvent.Up -> load(_state.value.path.substringBeforeLast('/').ifEmpty { PhoneFoldersUiState.ROOT })
      PhoneFoldersEvent.Reload -> load(_state.value.path)
      is PhoneFoldersEvent.Toggle ->
        _state.update { s ->
          val next = if (event.path in s.checked) s.checked - event.path else s.checked + event.path
          s.copy(checked = next.toImmutableSet())
        }
    }
  }

  private fun load(path: String) {
    loadJob?.cancel()
    _state.update { it.copy(path = path, loading = true, error = null) }
    loadJob =
      viewModelScope.launch {
        try {
          val entries = api.browse(serial, path).sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
          _state.update { it.copy(entries = entries.toImmutableList(), loading = false) }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          _state.update { it.copy(entries = persistentListOf(), loading = false, error = e.message.orEmpty()) }
        }
      }
  }
}
