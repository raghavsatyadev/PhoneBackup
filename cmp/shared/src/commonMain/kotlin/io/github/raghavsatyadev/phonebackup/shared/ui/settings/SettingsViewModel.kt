package io.github.raghavsatyadev.phonebackup.shared.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.data.SavedServer
import io.github.raghavsatyadev.phonebackup.shared.data.ServerConfig
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
  val address: String = "",
  val token: String = "",
  val saved: Boolean = true,
  val test: TestResult? = null,
)

sealed interface SettingsEvent {
  data class AddressChanged(val value: String) : SettingsEvent

  data class TokenChanged(val value: String) : SettingsEvent

  data object Save : SettingsEvent

  data object Test : SettingsEvent
}

class SettingsViewModel(private val settings: SettingsStore, private val uploads: UploadClient) :
  ViewModel() {
  private val _state =
    MutableStateFlow(settings.config.value.let { SettingsUiState(address = it.address, token = it.token) })
  val state: StateFlow<SettingsUiState> = _state.asStateFlow()
  private var testJob: Job? = null

  fun onEvent(event: SettingsEvent) {
    when (event) {
      is SettingsEvent.AddressChanged ->
        _state.update { it.copy(address = event.value, saved = false, test = null) }
      is SettingsEvent.TokenChanged ->
        _state.update { it.copy(token = event.value, saved = false, test = null) }
      SettingsEvent.Save -> save()
      SettingsEvent.Test -> test()
    }
  }

  private fun save(): ServerConfig {
    val config = ServerConfig(_state.value.address, _state.value.token)
    settings.update { list -> list.upsert((list.active ?: SavedServer()).copy(address = config.address.trim(), token = config.token.trim())) }
    _state.update { it.copy(saved = true) }
    return config
  }

  /** Testing also saves, so a working setup is never lost. */
  private fun test() {
    val config = save()
    testJob?.cancel()
    _state.update { it.copy(test = TestResult.Running) }
    testJob =
      viewModelScope.launch {
        val result =
          try {
            val ping = uploads.ping(config)
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
