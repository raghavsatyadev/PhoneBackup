package io.github.raghavsatyadev.phonebackup.shared.ui.servers

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.data.FoundServer
import io.github.raghavsatyadev.phonebackup.shared.data.ServerList
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Immutable
data class SavedRow(
  val localId: String,
  val label: String,
  val address: String,
  val paired: Boolean,
  val active: Boolean,
)

/** A PC on the network; [savedId] is set when it is already in the list. */
@Immutable data class FoundRow(val server: FoundServer, val savedId: String?, val paired: Boolean)

@Immutable
data class ServersUiState(
  val saved: ImmutableList<SavedRow> = persistentListOf(),
  val found: ImmutableList<FoundRow> = persistentListOf(),
  /** False where the app can't search the network (desktop): only "add by address". */
  val canSearch: Boolean = false,
)

sealed interface ServersEvent {
  data class Activate(val localId: String) : ServersEvent

  data class Remove(val localId: String) : ServersEvent
}

class ServersViewModel(
  private val settings: SettingsStore,
  found: StateFlow<List<FoundServer>>,
  private val canSearch: Boolean,
) : ViewModel() {
  val state: StateFlow<ServersUiState> =
    combine(settings.servers, found) { list, net -> toState(list, net, canSearch) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), toState(settings.servers.value, found.value, canSearch))

  fun onEvent(event: ServersEvent) {
    when (event) {
      is ServersEvent.Activate -> settings.update { it.activate(event.localId) }
      is ServersEvent.Remove -> settings.update { it.remove(event.localId) }
    }
  }
}

internal fun toState(list: ServerList, net: List<FoundServer>, canSearch: Boolean): ServersUiState {
  val activeId = list.active?.localId
  val saved =
    list.servers.map { s ->
      SavedRow(s.localId, s.label, s.address, paired = s.token.isNotBlank(), active = s.localId == activeId)
    }
  val found =
    net.map { pc ->
      val match = list.servers.find { it.serverId.isNotBlank() && it.serverId == pc.serverId }
      FoundRow(pc, match?.localId, paired = match?.token?.isNotBlank() == true)
    }
  return ServersUiState(saved.toImmutableList(), found.toImmutableList(), canSearch)
}
