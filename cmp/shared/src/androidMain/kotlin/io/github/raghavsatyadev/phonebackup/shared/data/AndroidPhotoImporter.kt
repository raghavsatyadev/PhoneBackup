package io.github.raghavsatyadev.phonebackup.shared.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import io.github.raghavsatyadev.phonebackup.shared.model.DateSource
import io.github.raghavsatyadev.phonebackup.shared.model.IncomingPhoto
import java.io.File
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Copies shared photos into app storage right away (the URI grant ends with the activity)
 * and works out when each photo was taken. Layout: filesDir/incoming/<id>/<name> plus a
 * small ".meta" file so a restart can restore the queue with the same dates.
 */
@OptIn(ExperimentalTime::class)
class AndroidPhotoImporter(context: Context) {
  private val resolver: ContentResolver = context.contentResolver
  private val root = File(context.filesDir, "incoming")
  private val mutex = Mutex()
  private val known = HashSet<String>()

  /** [onProgress] gets (files handled so far, name of the last one) after each file. */
  suspend fun import(uris: List<Uri>, onProgress: (Int, String) -> Unit = { _, _ -> }): List<IncomingPhoto> =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        uris
          .filter { it.scheme == ContentResolver.SCHEME_CONTENT }
          .mapIndexedNotNull { index, uri ->
            val photo =
              runCatching { copy(uri) }.onFailure { Log.w(TAG, "Could not import $uri", it) }.getOrNull()
            onProgress(index + 1, photo?.fileName.orEmpty())
            photo
          }
          .onEach { known += it.id }
      }
    }

  /** Photos left over from a previous run that were never uploaded or removed. */
  suspend fun restore(): List<IncomingPhoto> =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        root
          .listFiles { f -> f.isDirectory && f.name !in known }
          .orEmpty()
          .sortedBy { it.lastModified() }
          .mapNotNull { dir -> runCatching { restoreDir(dir) }.getOrNull() }
          .onEach { known += it.id }
      }
    }

  private fun copy(uri: Uri): IncomingPhoto {
    val info = query(uri)
    val id = UUID.randomUUID().toString()
    val dir = File(root, id).apply { mkdirs() }
    val file = File(dir, safeName(info.name, resolver.getType(uri)))
    try {
      resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    } catch (e: Exception) {
      dir.deleteRecursively()
      throw e
    }
    val (takenAt, source) = detectDate(file, info)
    File(dir, META).writeText("$takenAt|${source.name}")
    return IncomingPhoto(id, file.name, file.absolutePath, file.length(), takenAt, source)
  }

  private fun restoreDir(dir: File): IncomingPhoto? {
    val file = dir.listFiles { f -> f.isFile && f.name != META }?.firstOrNull()
    if (file == null) {
      dir.deleteRecursively()
      return null
    }
    val meta = File(dir, META).takeIf { it.exists() }?.readText()?.split('|')
    val saved =
      meta?.let { (date, src) ->
        runCatching { LocalDateTime.parse(date) to DateSource.valueOf(src) }.getOrNull()
      }
    val (takenAt, source) = saved ?: detectDate(file, SourceInfo(file.name, null, null))
    return IncomingPhoto(dir.name, file.name, file.absolutePath, file.length(), takenAt, source)
  }

  private fun detectDate(file: File, info: SourceInfo): Pair<LocalDateTime, DateSource> {
    val exif = runCatching { ExifInterface(file) }.getOrNull()
    PhotoDates.parseExif(exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))?.let {
      return it to DateSource.Exif
    }
    PhotoDates.parseExif(exif?.getAttribute(ExifInterface.TAG_DATETIME))?.let {
      return it to DateSource.Exif
    }
    info.takenMillis?.let { return local(it) to DateSource.MediaStore }
    PhotoDates.parseFileName(info.name)?.let { return it to DateSource.FileName }
    info.modifiedMillis?.let { return local(it) to DateSource.FileTime }
    return local(Clock.System.now().toEpochMilliseconds()) to DateSource.Unknown
  }

  private fun local(millis: Long) =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())

  private class SourceInfo(val name: String, val takenMillis: Long?, val modifiedMillis: Long?)

  private fun query(uri: Uri): SourceInfo {
    var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
    runCatching {
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.let { name = it }
      }
    }
    var taken: Long? = null
    var modified: Long? = null
    // Not every provider has MediaStore columns; an unknown column throws, so ask separately.
    runCatching {
      val cols = arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED)
      resolver.query(uri, cols, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          if (!c.isNull(0)) taken = c.getLong(0).takeIf { it > 0 }
          if (!c.isNull(1)) modified = c.getLong(1).takeIf { it > 0 }?.times(1000)
        }
      }
    }
    return SourceInfo(name, taken, modified)
  }

  private fun safeName(raw: String, mime: String?): String {
    var name = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.')
    if (name.isEmpty()) name = "photo_${System.currentTimeMillis()}"
    if (!name.contains('.')) {
      MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
    }
    return name.take(150)
  }

  private companion object {
    const val META = ".meta"
    const val TAG = "PhotoImporter"
  }
}
