package io.github.raghavsatyadev.phonebackup.shared.sample

import io.github.raghavsatyadev.phonebackup.shared.data.FolderInfo
import io.github.raghavsatyadev.phonebackup.shared.data.GalleryPhoto
import io.github.raghavsatyadev.phonebackup.shared.data.PairStatus
import io.github.raghavsatyadev.phonebackup.shared.data.PairTicket
import io.github.raghavsatyadev.phonebackup.shared.data.PingResponse
import io.github.raghavsatyadev.phonebackup.shared.data.UploadResponse
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

@Serializable private data class SampleGallery(val photos: List<GalleryPhoto>)

@Serializable private data class SampleAlbums(val albums: List<String>)

/**
 * The PC of sample mode, in memory: it answers the same API as server/upload.go, pair.go and
 * gallery.go from a [MockEngine], so every screen runs its real code with nothing on the network.
 * Uploaded photos are kept (newest few only) so they show up in the Library.
 */
class SampleServer {
  private val json = Json { encodeDefaults = true }
  private val mutex = Mutex()
  private val uploads = ArrayList<Upload>()
  private val bundled = HashMap<String, ByteArray>()
  private var pairPolls = 0

  private class Upload(val photo: GalleryPhoto, val bytes: ByteArray?)

  fun client(): HttpClient = HttpClient(MockEngine { request -> handle(request) }) { expectSuccess = false }

  private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
    // Long enough for loading states to show, short enough not to annoy.
    delay(RESPONSE_DELAY_MS)
    val path = request.url.encodedPath
    if (path.startsWith("/api/pair/")) return pair(path)
    if (request.headers[HttpHeaders.Authorization] != "Bearer ${SamplePc.TOKEN}") {
      return error(HttpStatusCode.Unauthorized, "Pair this phone with the PC first.")
    }
    return when (path) {
      "/api/upload/ping" -> ok(PingResponse.serializer(), PingResponse(true, SamplePc.NAME, SamplePc.ID, FOLDER))
      "/api/upload/folder" -> ok(FolderInfo.serializer(), FolderInfo(FOLDER, canChange = false))
      "/api/upload/albums" -> ok(SampleAlbums.serializer(), SampleAlbums(albums()))
      "/api/upload" -> upload(request)
      "/api/gallery/photos" -> ok(SampleGallery.serializer(), SampleGallery(photos()))
      "/api/gallery/thumb", "/api/gallery/file" -> image(request.url.parameters["id"].orEmpty())
      else -> error(HttpStatusCode.NotFound, "not found")
    }
  }

  /** The PC "allows" the phone on the second poll, so the code stays on screen for a moment. */
  private suspend fun MockRequestHandleScope.pair(path: String): HttpResponseData =
    mutex.withLock {
      when (path) {
        "/api/pair/request" -> {
          pairPolls = 0
          ok(PairTicket.serializer(), PairTicket(secret = "sample", code = SamplePc.PAIR_CODE))
        }
        "/api/pair/status" -> {
          pairPolls++
          val status =
            if (pairPolls < 2) PairStatus("pending")
            else PairStatus("allowed", token = SamplePc.TOKEN, serverId = SamplePc.ID, host = SamplePc.NAME)
          ok(PairStatus.serializer(), status)
        }
        else -> error(HttpStatusCode.NotFound, "not found")
      }
    }

  private suspend fun MockRequestHandleScope.upload(request: HttpRequestData): HttpResponseData {
    // Reading the body drives the client's upload progress, like a real transfer.
    val body = request.body.toByteArray()
    val boundary = request.body.contentType?.parameter("boundary") ?: return error(HttpStatusCode.BadRequest, "no form")
    val parts = parseMultipart(body, boundary)
    val file = parts["file"] ?: return error(HttpStatusCode.BadRequest, "no file")
    val album = parts["album"]?.text()?.let(::cleanName)?.ifBlank { null } ?: "Unsorted"
    val wanted = parts["fileName"]?.text()?.let(::cleanName)?.ifBlank { null } ?: "photo.jpg"
    val takenAt = parts["takenAt"]?.text().orEmpty()
    val tags = parts["tags"]?.text().orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return mutex.withLock {
      val same = uploads.find { it.photo.album == album && it.photo.fileName == wanted }
      if (same != null && same.photo.size == file.size.toLong()) {
        return@withLock ok(UploadResponse.serializer(), UploadResponse(wanted, album, skipped = true, size = file.size.toLong()))
      }
      val name = freeName(album, wanted)
      val photo =
        GalleryPhoto(
          id = "$album/$name",
          album = album,
          fileName = name,
          takenAt = takenAt,
          uploadedAt = takenAt,
          size = file.size.toLong(),
          tags = tags,
          category = "Everyday",
          caption = "Sent from this phone.",
        )
      uploads.add(0, Upload(photo, file.takeIf { it.size <= MAX_KEPT_BYTES }))
      // Memory stays small: only the newest uploads keep their picture.
      for (i in KEEP_PICTURES until uploads.size) uploads[i] = Upload(uploads[i].photo, null)
      ok(UploadResponse.serializer(), UploadResponse(name, album, skipped = false, size = file.size.toLong()))
    }
  }

  private fun freeName(album: String, wanted: String): String {
    val taken = uploads.filter { it.photo.album == album }.map { it.photo.fileName }.toSet()
    if (wanted !in taken) return wanted
    val dot = wanted.lastIndexOf('.').takeIf { it > 0 } ?: wanted.length
    return generateSequence(1) { it + 1 }.map { "${wanted.take(dot)} ($it)${wanted.drop(dot)}" }.first { it !in taken }
  }

  private suspend fun photos(): List<GalleryPhoto> =
    mutex.withLock { uploads.map { it.photo } + samplePhotos.map { it.photo } }
      .sortedByDescending { runCatching { Instant.parse(it.takenAt) }.getOrNull() ?: Instant.DISTANT_PAST }

  private suspend fun albums(): List<String> =
    mutex.withLock { (uploads.map { it.photo.album } + samplePhotos.map { it.photo.album }).distinct() }

  @OptIn(ExperimentalResourceApi::class)
  private suspend fun MockRequestHandleScope.image(id: String): HttpResponseData {
    val upload = mutex.withLock { uploads.find { it.photo.id == id } }
    val file = samplePhotos.find { it.photo.id == id }?.file ?: if (upload != null) samplePhotos.first().file else null
    val bytes =
      upload?.bytes
        ?: file?.let { name -> bundled[name] ?: Res.readBytes("files/sample/$name").also { bundled[name] = it } }
        ?: return error(HttpStatusCode.NotFound, "no such photo")
    return respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
  }

  private fun <T> MockRequestHandleScope.ok(serializer: KSerializer<T>, value: T): HttpResponseData =
    respond(json.encodeToString(serializer, value), HttpStatusCode.OK, JSON_HEADERS)

  private fun MockRequestHandleScope.error(status: HttpStatusCode, message: String): HttpResponseData =
    respond("""{"error":${json.encodeToString(message)}}""", status, JSON_HEADERS)

  private companion object {
    const val FOLDER = "PhoneBackup (sample)"
    const val RESPONSE_DELAY_MS = 250L
    const val KEEP_PICTURES = 20
    const val MAX_KEPT_BYTES = 25 * 1024 * 1024
    val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
  }
}

private fun ByteArray.text(): String = decodeToString()

/** Like the server's sanitizeName, enough for display: no folders, no control characters. */
private fun cleanName(raw: String): String =
  raw.trim().replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim('.', ' ')

/** Splits a multipart/form-data body into field name -> bytes. */
internal fun parseMultipart(body: ByteArray, boundary: String): Map<String, ByteArray> {
  val delimiter = "--$boundary".encodeToByteArray()
  val headerEnd = "\r\n\r\n".encodeToByteArray()
  val result = HashMap<String, ByteArray>()
  var start = body.indexOf(delimiter, 0)
  while (start >= 0) {
    val partStart = start + delimiter.size
    // "--" right after the delimiter ends the body.
    if (partStart + 1 < body.size && body[partStart] == '-'.code.toByte() && body[partStart + 1] == '-'.code.toByte()) break
    val next = body.indexOf(delimiter, partStart)
    if (next < 0) break
    val headersAt = body.indexOf(headerEnd, partStart)
    if (headersAt in 0 until next) {
      val headers = body.copyOfRange(partStart, headersAt).decodeToString()
      val name = Regex("""name="([^"]*)"""").find(headers)?.groupValues?.get(1)
      // The part's data ends with the CRLF that comes before the next delimiter.
      val dataEnd = (next - 2).coerceAtLeast(headersAt + headerEnd.size)
      if (name != null) result[name] = body.copyOfRange(headersAt + headerEnd.size, dataEnd)
    }
    start = next
  }
  return result
}

private fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
  val last = size - needle.size
  var i = from
  outer@ while (i <= last) {
    for (j in needle.indices) if (this[i + j] != needle[j]) {
      i++
      continue@outer
    }
    return i
  }
  return -1
}
