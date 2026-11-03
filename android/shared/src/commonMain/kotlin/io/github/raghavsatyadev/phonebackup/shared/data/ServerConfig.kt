package io.github.raghavsatyadev.phonebackup.shared.data

import kotlinx.coroutines.flow.StateFlow

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

  companion object {
    const val DEFAULT_PORT = 9877
  }
}

/** Persists the server address and token. Implemented per platform. */
interface SettingsStore {
  val config: StateFlow<ServerConfig>

  fun save(config: ServerConfig)
}
