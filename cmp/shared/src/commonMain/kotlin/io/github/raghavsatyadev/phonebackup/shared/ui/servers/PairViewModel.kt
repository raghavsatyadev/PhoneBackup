package io.github.raghavsatyadev.phonebackup.shared.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.data.PairClient
import io.github.raghavsatyadev.phonebackup.shared.data.SavedServer
import io.github.raghavsatyadev.phonebackup.shared.data.ServerList
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairState {
  data object Asking : PairState

  /** The PC shows the same [code] with an Allow button. */
  data class Waiting(val code: String) : PairState

  data class Allowed(val name: String) : PairState

  data object Denied : PairState

  data object Expired : PairState

  data class Failed(val reason: String) : PairState
}

/**
 * Asks the PC at [address] to allow this device, then waits for the answer. The poll secret
 * lives only in this ViewModel, so it is never saved or put in the back stack.
 */
class PairViewModel(
  private val pairing: PairClient,
  private val settings: SettingsStore,
  private val deviceName: String,
  private val address: String,
  private val name: String,
) : ViewModel() {
  private val _state = MutableStateFlow<PairState>(PairState.Asking)
  val state: StateFlow<PairState> = _state.asStateFlow()
  private var job: Job? = null

  init {
    start()
  }

  fun start() {
    job?.cancel()
    _state.value = PairState.Asking
    job = viewModelScope.launch { run() }
  }

  private suspend fun run() {
    val ticket =
      try {
        pairing.request(address, deviceName)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.value = PairState.Failed(e.message ?: e.toString())
        return
      }
    _state.value = PairState.Waiting(ticket.code)
    val deadline = TimeSource.Monotonic.markNow() + ticket.expiresIn.seconds + 5.seconds
    var failures = 0
    while (deadline.hasNotPassedNow()) {
      delay(POLL_EVERY)
      val status =
        try {
          pairing.status(address, ticket.secret).also { failures = 0 }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Wi-Fi blips are normal while the user walks to the PC.
          if (++failures >= MAX_FAILURES) {
            _state.value = PairState.Failed(e.message ?: e.toString())
            return
          }
          continue
        }
      when (status.status) {
        "pending" -> Unit
        "allowed" -> {
          val label = status.host.ifBlank { name }
          settings.update { savePaired(it, address, label, status.serverId, status.token) }
          _state.value = PairState.Allowed(label)
          return
        }
        "denied" -> {
          _state.value = PairState.Denied
          return
        }
        else -> {
          _state.value = PairState.Expired
          return
        }
      }
    }
    _state.value = PairState.Expired
  }

  private companion object {
    val POLL_EVERY = 1500.milliseconds
    const val MAX_FAILURES = 5
  }
}

/** Saves the paired PC (merging with an entry for the same PC or address) and makes it active. */
internal fun savePaired(list: ServerList, address: String, name: String, serverId: String, token: String): ServerList {
  val existing =
    list.servers.find { serverId.isNotBlank() && it.serverId == serverId }
      ?: list.servers.find { it.address.equals(address, ignoreCase = true) }
  val entry =
    (existing ?: SavedServer()).copy(
      serverId = serverId.ifBlank { existing?.serverId.orEmpty() },
      name = existing?.name?.ifBlank { null } ?: name,
      address = address,
      token = token,
    )
  val next = list.upsert(entry)
  val saved = next.servers.find { it.serverId.isNotBlank() && it.serverId == entry.serverId } ?: next.find(entry.localId)
  return saved?.let { next.activate(it.localId) } ?: next
}
