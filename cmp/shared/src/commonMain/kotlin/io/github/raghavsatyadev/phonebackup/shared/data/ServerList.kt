package io.github.raghavsatyadev.phonebackup.shared.data

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * A PC the app can send photos to. [localId] is ours; [serverId] is the PC's own id (from the
 * network announcement or the ping), blank until known, and lets us follow the PC to a new IP.
 */
@Serializable
data class SavedServer(
  val localId: String = newLocalId(),
  val serverId: String = "",
  val name: String = "",
  val address: String = "",
  val token: String = "",
) {
  val config: ServerConfig
    get() = ServerConfig(address, token)

  /** The name to show: the PC's name, or its address when it has none. */
  val label: String
    get() = name.ifBlank { address }
}

@OptIn(ExperimentalUuidApi::class) fun newLocalId(): String = Uuid.random().toString()

/** Every saved PC and the one that receives photos. Pure, so it is easy to test. */
@Serializable
data class ServerList(val servers: List<SavedServer> = emptyList(), val activeId: String? = null) {
  /** The PC photos go to; the first one if the chosen one was removed. */
  val active: SavedServer?
    get() = servers.find { it.localId == activeId } ?: servers.firstOrNull()

  fun find(localId: String?): SavedServer? = servers.find { it.localId == localId }

  /**
   * Adds or replaces [server]. A server whose PC id is already saved under another entry
   * replaces that entry instead of making a second one. The first server saved becomes active.
   */
  fun upsert(server: SavedServer): ServerList {
    val sameEntry = servers.indexOfFirst { it.localId == server.localId }
    val samePc =
      if (server.serverId.isBlank()) -1 else servers.indexOfFirst { it.serverId == server.serverId }
    val index = if (sameEntry >= 0) sameEntry else samePc
    if (index < 0) {
      return copy(servers = servers + server, activeId = activeId ?: server.localId)
    }
    val replaced = servers[index]
    val merged = server.copy(localId = replaced.localId)
    // When both the edited entry and another entry point at the same PC, keep one.
    val rest = servers.filterIndexed { i, s -> i == index || s.serverId.isBlank() || s.serverId != merged.serverId }
    val kept = rest.map { if (it.localId == replaced.localId) merged else it }
    // The active entry may be the one just merged away: the merged entry takes its place.
    val active = activeId?.takeIf { id -> kept.any { it.localId == id } } ?: replaced.localId
    return copy(servers = kept, activeId = active)
  }

  fun remove(localId: String): ServerList {
    val left = servers.filterNot { it.localId == localId }
    return ServerList(left, if (activeId == localId) left.firstOrNull()?.localId else activeId)
  }

  fun activate(localId: String): ServerList = if (find(localId) == null) this else copy(activeId = localId)

  /**
   * The PC [serverId] was seen on the network at [hosts]. A saved address that is no longer one
   * of them (the router gave the PC a new IP) moves to the first; one that still matches stays,
   * so a PC with several addresses doesn't flip between them.
   */
  fun seenAt(serverId: String, hosts: List<String>, port: Int): ServerList {
    if (serverId.isBlank() || hosts.isEmpty()) return this
    var changed = false
    val updated =
      servers.map { s ->
        if (s.serverId != serverId || s.config.host in hosts) return@map s
        changed = true
        s.copy(address = ServerConfig.addressOf(hosts.first(), port))
      }
    return if (changed) copy(servers = updated) else this
  }
}
