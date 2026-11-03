package io.github.raghavsatyadev.phonebackup.shared.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/** A PC announcing the upload API on this network (mDNS `_phonebackup._tcp`). */
data class FoundServer(val serverId: String, val name: String, val hosts: List<String>, val port: Int) {
  /** What to save: the first IPv4 address, and the port only when it isn't the default. */
  val address: String
    get() = ServerConfig.addressOf(hosts.first(), port)
}

/** Looks for PCs on the network while collected. */
fun interface ServerDiscovery {
  fun found(): Flow<List<FoundServer>>
}

/** For platforms without discovery (the desktop viewer): PCs are added by address. */
val NoDiscovery = ServerDiscovery { flowOf(emptyList()) }

/**
 * The PCs seen on the network right now. While [watch] runs, it also moves a saved PC to its
 * new address when the router gives that PC a new IP.
 */
class NetworkServers(private val discovery: ServerDiscovery, private val settings: SettingsStore) {
  private val _found = MutableStateFlow<List<FoundServer>>(emptyList())
  val found: StateFlow<List<FoundServer>> = _found.asStateFlow()

  suspend fun watch() {
    try {
      discovery.found().collect { list ->
        _found.value = list
        settings.update { saved -> list.fold(saved) { acc, pc -> acc.seenAt(pc.serverId, pc.hosts, pc.port) } }
      }
    } finally {
      _found.value = emptyList()
    }
  }
}
