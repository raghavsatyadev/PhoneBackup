package io.github.raghavsatyadev.phonebackup.shared.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PingResponse(val ok: Boolean = false, val host: String = "", val id: String = "", val uploadRoot: String = "")

@Serializable
data class FolderInfo(val path: String = "", val canChange: Boolean = false)

@Serializable
private data class AlbumList(val albums: List<String> = emptyList())

@Serializable
data class UploadResponse(val fileName: String = "", val album: String = "", val skipped: Boolean = false, val size: Long = 0)

class UploadException(message: String) : Exception(message)

/** Talks to the PhoneBackup PC server's upload API (see upload.go). */
class UploadClient(private val http: HttpClient) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun ping(config: ServerConfig): PingResponse {
    val res = http.get("${config.baseUrl}/api/upload/ping") { auth(config) }
    return json.decodeFromString(PingResponse.serializer(), res.checked())
  }

  /** Where the PC saves photos, and whether this caller (an app on that PC) may change it. */
  suspend fun folder(config: ServerConfig): FolderInfo {
    val res = http.get("${config.baseUrl}/api/upload/folder") { auth(config) }
    return json.decodeFromString(FolderInfo.serializer(), res.checked())
  }

  /** Album folders already on the PC, most recently used first. */
  suspend fun albums(config: ServerConfig): List<String> {
    val res = http.get("${config.baseUrl}/api/upload/albums") { auth(config) }
    return json.decodeFromString(AlbumList.serializer(), res.checked()).albums
  }

  /** Changes the photo folder; the PC refuses unless the request comes from the PC itself. */
  suspend fun setFolder(config: ServerConfig, path: String): FolderInfo {
    val res =
      http.post("${config.baseUrl}/api/upload/folder") {
        auth(config)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(mapOf("path" to path)))
      }
    return json.decodeFromString(FolderInfo.serializer(), res.checked())
  }

  /** Streams one file from disk; [onProgress] gets (bytesSent, totalBytes). */
  suspend fun upload(
    config: ServerConfig,
    path: String,
    fileName: String,
    sizeBytes: Long,
    album: String,
    tags: List<String>,
    takenAt: LocalDateTime,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
  ): UploadResponse {
    val instant = takenAt.toInstant(TimeZone.currentSystemDefault())
    val form = formData {
      // Text fields first: the server reads them before the file part.
      append("album", album)
      append("tags", tags.joinToString(","))
      append("takenAt", instant.toString())
      append("fileName", fileName)
      append(
        "file",
        InputProvider(sizeBytes) { SystemFileSystem.source(Path(path)).buffered() },
        Headers.build {
          append(HttpHeaders.ContentType, "application/octet-stream")
          append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "_")}\"")
        },
      )
    }
    val res =
      http.submitFormWithBinaryData("${config.baseUrl}/api/upload", form) {
        auth(config)
        onUpload { sent, total -> onProgress(sent, total ?: sizeBytes) }
      }
    return json.decodeFromString(UploadResponse.serializer(), res.checked())
  }

  private fun io.ktor.client.request.HttpRequestBuilder.auth(config: ServerConfig) =
    header(HttpHeaders.Authorization, "Bearer ${config.token.trim()}")

  private suspend fun HttpResponse.checked(): String {
    val body = bodyAsText()
    if (!status.isSuccess()) {
      val detail = runCatching { json.decodeFromString<Map<String, String>>(body)["error"] }.getOrNull()
      throw UploadException("HTTP ${status.value}${detail?.let { ": $it" } ?: ""}")
    }
    return body
  }
}
