package io.github.raghavsatyadev.phonebackup.shared.model

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDateTime

/** Where a photo's date came from, so the user knows how much to trust it. */
enum class DateSource {
  Exif,
  MediaStore,
  FileName,
  FileTime,
  Manual,
  Unknown,
}

enum class UploadStatus {
  Pending,
  Uploading,
  Done,
  Skipped,
  Failed,
}

/** A shared photo already copied into app storage, with the date we detected for it. */
@Immutable
data class IncomingPhoto(
  val id: String,
  val fileName: String,
  val path: String,
  val sizeBytes: Long,
  val takenAt: LocalDateTime,
  val dateSource: DateSource,
)

@Immutable
data class PhotoItem(
  val id: String,
  val fileName: String,
  val path: String,
  val sizeBytes: Long,
  val takenAt: LocalDateTime,
  val dateSource: DateSource,
  val status: UploadStatus = UploadStatus.Pending,
  val error: String? = null,
)

fun IncomingPhoto.toItem() =
  PhotoItem(
    id = id,
    fileName = fileName,
    path = path,
    sizeBytes = sizeBytes,
    takenAt = takenAt,
    dateSource = dateSource,
  )
