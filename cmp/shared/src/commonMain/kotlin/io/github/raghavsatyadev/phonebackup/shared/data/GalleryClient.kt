package io.github.raghavsatyadev.phonebackup.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One photo from the PC server's /api/gallery/photos (see server/gallery.go). */
@Serializable
data class GalleryPhoto(
  val id: String,
  val album: String = "",
  val fileName: String = "",
  val takenAt: String = "",
  val uploadedAt: String = "",
  val size: Long = 0,
  val isVideo: Boolean = false,
  val tags: List<String> = emptyList(),
  val aiTags: List<String> = emptyList(),
  val caption: String = "",
  val text: String = "",
  val category: String = "",
  val event: String = "",
  val groupId: String = "",
  val groupSize: Int = 1,
  val isBest: Boolean = true,
)

@Serializable private data class GalleryResponse(val photos: List<GalleryPhoto> = emptyList())

/** Read-only access to the photos on the PC. */
class GalleryClient(private val http: HttpClient) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun photos(config: ServerConfig): List<GalleryPhoto> {
    val res = http.get("${config.baseUrl}/api/gallery/photos") {
      header(HttpHeaders.Authorization, "Bearer ${config.token.trim()}")
    }
    val body = res.bodyAsText()
    if (!res.status.isSuccess()) {
      val detail = runCatching { json.decodeFromString<Map<String, String>>(body)["error"] }.getOrNull()
      throw UploadException("HTTP ${res.status.value}${detail?.let { ": $it" } ?: ""}")
    }
    return json.decodeFromString(GalleryResponse.serializer(), body).photos
  }

  companion object {
    /** Preview URL; the token goes in a header added by [authedImageClient], never in the URL. */
    fun thumbUrl(config: ServerConfig, id: String, large: Boolean = false): String =
      "${config.baseUrl}/api/gallery/thumb?id=${id.encodeURLParameter()}&size=${if (large) "large" else "small"}"
  }
}

/**
 * An HTTP client for image loading that adds the current token to every request, read
 * at request time so a new token in Settings works without restarting.
 */
fun authedImageClient(settings: SettingsStore): HttpClient =
  createHttpClient().config {
    install(
      createClientPlugin("PhoneBackupAuth") {
        onRequest { request, _ ->
          val token = settings.config.value.token.trim()
          if (token.isNotEmpty()) request.header(HttpHeaders.Authorization, "Bearer $token")
        }
      }
    )
  }
