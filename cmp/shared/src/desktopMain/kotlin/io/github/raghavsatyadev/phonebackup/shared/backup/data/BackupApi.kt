package io.github.raghavsatyadev.phonebackup.shared.backup.data

import io.github.raghavsatyadev.phonebackup.shared.data.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A refused request; [message] is the server's own error text. */
class BackupException(message: String) : Exception(message)

/**
 * The backup engine's API on this PC (server/server.go). The server only listens on
 * 127.0.0.1, so no token is needed.
 */
class BackupApi(private val baseUrl: String = DEFAULT_URL) {
  private val http: HttpClient =
    createHttpClient().config {
      // OkHttp drops a socket that is silent for 60 s, and the server never pings.
      install(WebSockets) { pingIntervalMillis = 20_000 }
    }

  suspend fun devices(): List<AdbDevice> =
    get("/api/devices", ListSerializer(AdbDevice.serializer()).nullable)
      .orEmpty()
      .filter { it.state.isEmpty() || it.state == "device" }

  suspend fun connectWifi(address: String): String = message(post("/api/devices/connect", body { put("address", address) }))

  suspend fun disconnectWifi(address: String): String =
    message(post("/api/devices/disconnect", body { put("address", address) }))

  suspend fun browse(serial: String, path: String): List<RemoteEntry> =
    get("/api/browse", ListSerializer(RemoteEntry.serializer()).nullable) {
        parameter("serial", serial)
        parameter("path", path)
      }
      .orEmpty()

  suspend fun setDestination(path: String): DestinationInfo =
    decode(post("/api/local/set-destination", body { put("path", path) }), DestinationInfo.serializer())

  suspend fun transfers(): List<Transfer> = get("/api/transfer/list", ListSerializer(Transfer.serializer()).nullable).orEmpty()

  suspend fun start(serial: String, sources: List<String>, localDir: String, include: List<String>): Transfer =
    decode(
      post(
        "/api/transfer/start",
        body {
          put("active_serial", serial)
          putJsonArray("sources") { sources.forEach { add(JsonPrimitive(it)) } }
          put("local_dir", localDir)
          putJsonObject("filters") { putJsonArray("include") { include.forEach { add(JsonPrimitive(it)) } } }
        },
      ),
      Transfer.serializer(),
    )

  suspend fun pause(id: String) {
    check(post("/api/transfer/pause/$id", null))
  }

  suspend fun cancel(id: String) {
    check(post("/api/transfer/cancel/$id", null))
  }

  suspend fun resume(id: String, serial: String): Transfer =
    decode(post("/api/transfer/resume/$id", body { put("active_serial", serial) }), Transfer.serializer())

  /** Queues the failed files again; returns how many. */
  suspend fun retryFailed(id: String, serial: String): Int =
    decode(post("/api/transfer/retry-failed/$id", body { put("active_serial", serial) }), RetryResult.serializer()).retriedCount

  suspend fun failed(id: String): List<FileItem> = get("/api/transfer/$id/failed", FailedResult.serializer()).items

  /** Loads a `phonebackup_status.json` (or the folder holding one) as a paused backup. */
  suspend fun import(filePath: String): Transfer? =
    decode(post("/api/transfer/import", body { put("filePath", filePath) }), ImportResult.serializer()).transfer

  /**
   * Streams transfer updates until the socket closes or fails. [onOpen] runs once connected, so
   * the caller can catch up on what it missed. Each update carries the whole file catalog, so
   * it is decoded off the main thread.
   */
  suspend fun watch(onOpen: suspend () -> Unit, onUpdate: suspend (Transfer) -> Unit) {
    http.webSocket(baseUrl.replaceFirst("http", "ws") + "/ws") {
      onOpen()
      for (frame in incoming) {
        if (frame !is Frame.Text) continue
        val text = frame.readText()
        if (!text.contains("\"transfer_update\"")) continue
        val transfer =
          withContext(Dispatchers.Default) {
            runCatching { BackupJson.decodeFromString(SocketMessage.serializer(), text) }.getOrNull()?.transfer
          }
        if (transfer != null) onUpdate(transfer)
      }
    }
  }

  private suspend fun <T> get(path: String, serializer: KSerializer<T>, block: HttpRequestBuilder.() -> Unit = {}): T =
    decode(http.get(baseUrl + path, block), serializer)

  private suspend fun post(path: String, body: JsonObject?): HttpResponse =
    http.post(baseUrl + path) {
      contentType(ContentType.Application.Json)
      setBody((body ?: JsonObject(emptyMap())).toString())
    }

  private suspend fun <T> decode(res: HttpResponse, serializer: KSerializer<T>): T {
    val text = check(res)
    return withContext(Dispatchers.Default) { BackupJson.decodeFromString(serializer, text) }
  }

  private suspend fun message(res: HttpResponse): String {
    val text = check(res)
    return runCatching { BackupJson.decodeFromString(ServerMessage.serializer(), text).message }.getOrDefault("")
  }

  /** Returns the body, or throws [BackupException] with the server's error text. */
  private suspend fun check(res: HttpResponse): String {
    val text = res.bodyAsText()
    if (res.status.isSuccess()) return text
    val detail =
      runCatching { BackupJson.decodeFromString(ServerMessage.serializer(), text).error }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: text.trim().ifBlank { "HTTP ${res.status.value}" }
    throw BackupException(detail)
  }

  private fun body(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

  companion object {
    const val DEFAULT_URL = "http://127.0.0.1:9876"
  }
}
