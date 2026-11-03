package io.github.raghavsatyadev.phonebackup.shared.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.data.SavedServer
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.shared.data.UploadClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TestResult {
  data object Running : TestResult

  data class Ok(val host: String, val folder: String) : TestResult

  data class Failed(val reason: String) : TestResult
}

@Immutable
data class SettingsUiState(
  val name: String = "",
  val address: String = "",
  val token: String = "",
  val saved: Boolean = true,
  val test: TestResult? = null,
)

sealed interface SettingsEvent {
  data class NameChanged(val value: String) : SettingsEvent

  data class AddressChanged(val value: String) : SettingsEvent

  data class TokenChanged(val value: String) : SettingsEvent

  data object Save : SettingsEvent

  data object Test : SettingsEvent
}

/**
 * Adds a PC by address, or edits the saved PC [localId]. A new entry is saved (and made active
 * when it is the first) on the first Save, Test or Pair.
 */
class SettingsViewModel(
  private val settings: SettingsStore,
  private val uploads: UploadClient,
  localId: String?,
) : ViewModel() {
  private val start = settings.servers.value.find(localId) ?: SavedServer()
  private var entryId = start.localId
  private val _state =
    MutableStateFlow(SettingsUiState(name = start.name, address = start.address, token = start.token, saved = localId != null))
  val state: StateFlow<SettingsUiState> = _state.asStateFlow()
  private var testJob: Job? = null

  fun onEvent(event: SettingsEvent) {
    when (event) {
      is SettingsEvent.NameChanged -> _state.update { it.copy(name = event.value, saved = false) }
      is SettingsEvent.AddressChanged ->
        _state.update { it.copy(address = event.value, saved = false, test = null) }
      is SettingsEvent.TokenChanged ->
        _state.update { it.copy(token = event.value, saved = false, test = null) }
      SettingsEvent.Save -> save()
      SettingsEvent.Test -> test()
    }
  }

  /** Saves what is typed; returns the saved entry. */
  fun save(serverId: String? = null, host: String? = null): SavedServer {
    val s = _state.value
    var result: SavedServer? = null
    settings.update { list ->
      val old = list.find(entryId) ?: SavedServer(localId = entryId)
      val entry =
        old.copy(
          serverId = serverId?.ifBlank { null } ?: old.serverId,
          name = s.name.trim().ifBlank { host.orEmpty() },
          address = s.address.trim(),
          token = s.token.trim(),
        )
      val next = list.upsert(entry)
      // upsert may merge into another entry for the same PC: follow it.
      val saved =
        next.find(entry.localId) ?: next.servers.firstOrNull { it.serverId.isNotBlank() && it.serverId == entry.serverId } ?: entry
      result = saved
      if (next.active == null) next.activate(saved.localId) else next
    }
    val saved = result ?: settings.servers.value.find(entryId) ?: SavedServer(localId = entryId)
    entryId = saved.localId
    _state.update { it.copy(name = saved.name, saved = true) }
    return saved
  }

  /** Testing also saves, so a working setup is never lost; a good answer fills in the PC's id. */
  private fun test() {
    val saved = save()
    testJob?.cancel()
    _state.update { it.copy(test = TestResult.Running) }
    testJob =
      viewModelScope.launch {
        val result =
          try {
            val ping = uploads.ping(saved.config)
            save(serverId = ping.id, host = ping.host)
            TestResult.Ok(ping.host, ping.uploadRoot)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            TestResult.Failed(e.message ?: e.toString())
          }
        _state.update { it.copy(test = result) }
      }
  }
}
