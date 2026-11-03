package io.github.raghavsatyadev.phonebackup.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the PC answers to a pairing request; [secret] stays in memory, never in saved state. */
@Serializable data class PairTicket(val secret: String = "", val code: String = "", val expiresIn: Int = 120)

/** "pending", "allowed" (with the token), "denied" or "expired". */
@Serializable
data class PairStatus(
  val status: String = "expired",
  val token: String = "",
  val serverId: String = "",
  val host: String = "",
)

/**
 * Asks a PC for its upload token (see server/pair.go). The PC shows the same code as the phone
 * and an Allow button; nobody types the token.
 */
class PairClient(private val http: HttpClient) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun request(address: String, deviceName: String): PairTicket =
    post(address, "/api/pair/request", """{"name":${json.encodeToString(deviceName)}}""", PairTicket.serializer())

  suspend fun status(address: String, secret: String): PairStatus =
    post(address, "/api/pair/status", """{"secret":${json.encodeToString(secret)}}""", PairStatus.serializer())

  private suspend fun <T> post(
    address: String,
    path: String,
    body: String,
    serializer: kotlinx.serialization.KSerializer<T>,
  ): T {
    val res =
      http.post(ServerConfig(address).baseUrl + path) {
        contentType(ContentType.Application.Json)
        setBody(body)
      }
    val text = res.bodyAsText()
    if (!res.status.isSuccess()) {
      val detail = runCatching { json.decodeFromString<Map<String, String>>(text)["error"] }.getOrNull()
      throw UploadException(detail ?: "HTTP ${res.status.value}")
    }
    return json.decodeFromString(serializer, text)
  }
}
