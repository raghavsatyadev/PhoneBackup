package io.github.raghavsatyadev.phonebackup.desktop.server

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A phone asking to pair, as the server's backup API lists it. */
@Serializable
data class PairRequest(val id: String, val name: String = "", val code: String = "", val ip: String = "")

/**
 * Phones waiting for "Allow this phone?", read from the local server's backup API (which only
 * answers this PC). Polls every 2 s, so the app can ask even while the window is hidden.
 */
class PairingRequests(private val scope: CoroutineScope) {
  private val _pending = MutableStateFlow<List<PairRequest>>(emptyList())
  val pending: StateFlow<List<PairRequest>> = _pending

  fun start() {
    scope.launch(Dispatchers.IO) {
      while (isActive) {
        fetch()?.let { _pending.value = it }
        delay(POLL_MS)
      }
    }
  }

  /** Answers a request; it leaves the list straight away. */
  fun decide(id: String, allow: Boolean) {
    _pending.value = _pending.value.filterNot { it.id == id }
    scope.launch(Dispatchers.IO) { post("/api/pair/decide", """{"id":${Json.encodeToString(id)},"allow":$allow}""") }
  }

  private fun fetch(): List<PairRequest>? =
    runCatching {
        val c = open("/api/pair/pending")
        if (c.responseCode != 200) return null
        json.decodeFromString<List<PairRequest>>(c.inputStream.bufferedReader().readText())
      }
      .getOrNull()

  private suspend fun post(path: String, body: String) =
    withContext(Dispatchers.IO) {
      runCatching {
        val c = open(path)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        c.responseCode
      }
    }

  private fun open(path: String) =
    (URI("http://127.0.0.1:$DASHBOARD_PORT$path").toURL().openConnection() as HttpURLConnection).apply {
      connectTimeout = 1000
      readTimeout = 2000
    }

  private companion object {
    const val DASHBOARD_PORT = 9876
    const val POLL_MS = 2000L
    val json = Json { ignoreUnknownKeys = true }
  }
}
