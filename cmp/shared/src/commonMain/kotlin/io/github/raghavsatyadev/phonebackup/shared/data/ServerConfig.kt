package io.github.raghavsatyadev.phonebackup.shared.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.Json

data class ServerConfig(val address: String = "", val token: String = "") {
  val isComplete: Boolean
    get() = address.isNotBlank() && token.isNotBlank()

  /** "192.168.1.5" -> "http://192.168.1.5:9877"; keeps an explicit scheme or port. */
  val baseUrl: String
    get() {
      var a = address.trim().trimEnd('/')
      if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
      val hostPart = a.substringAfter("://")
      if (!hostPart.contains(':')) a = "$a:$DEFAULT_PORT"
      return a
    }

  /** "http://192.168.1.5:9877" -> "192.168.1.5". */
  val host: String
    get() = baseUrl.substringAfter("://").substringBeforeLast(':')

  companion object {
    const val DEFAULT_PORT = 9877

    /** The address to save for [host]; the port only when it isn't the default. */
    fun addressOf(host: String, port: Int): String = if (port == DEFAULT_PORT) host else "$host:$port"
  }
}

/** Album and tags typed on the share screen but not uploaded yet. */
data class ShareDraft(val album: String = "", val tags: List<String> = emptyList())

/** A tiny string store: Android preferences or Java preferences. */
interface KeyValueStore {
  fun get(key: String): String?

  fun put(values: Map<String, String>)
}

/** The saved PCs, the active one, and the share draft. Shared by every platform. */
class SettingsStore(private val kv: KeyValueStore) {
  private val json = Json { ignoreUnknownKeys = true }
  private val list = MutableStateFlow(load())
  private val active = MutableStateFlow(list.value.active?.config ?: ServerConfig())
  val servers: StateFlow<ServerList> = list.asStateFlow()

  /** The PC photos go to. */
  val config: StateFlow<ServerConfig> = active.asStateFlow()

  fun update(change: (ServerList) -> ServerList) {
    val next = list.updateAndGet(change)
    kv.put(mapOf(KEY_SERVERS to json.encodeToString(next)))
    active.value = next.active?.config ?: ServerConfig()
  }

  /** Kept so album and tags survive Android stopping the app before an upload. */
  fun loadDraft() =
    ShareDraft(
      album = kv.get(KEY_ALBUM).orEmpty(),
      tags = kv.get(KEY_TAGS).orEmpty().split(',').filter { it.isNotBlank() },
    )

  fun saveDraft(draft: ShareDraft) {
    kv.put(mapOf(KEY_ALBUM to draft.album, KEY_TAGS to draft.tags.joinToString(",")))
  }

  /** Reads the list, or turns the single address and token of older versions into one entry. */
  private fun load(): ServerList {
    kv.get(KEY_SERVERS)?.let { saved ->
      return runCatching { json.decodeFromString<ServerList>(saved) }.getOrDefault(ServerList())
    }
    val address = kv.get(KEY_ADDRESS).orEmpty().trim()
    if (address.isBlank()) return ServerList()
    val migrated = ServerList().upsert(SavedServer(address = address, token = kv.get(KEY_TOKEN).orEmpty().trim()))
    kv.put(mapOf(KEY_SERVERS to json.encodeToString(migrated)))
    return migrated
  }

  private companion object {
    const val KEY_SERVERS = "servers"
    const val KEY_ADDRESS = "address"
    const val KEY_TOKEN = "token"
    const val KEY_ALBUM = "draft_album"
    const val KEY_TAGS = "draft_tags"
  }
}
