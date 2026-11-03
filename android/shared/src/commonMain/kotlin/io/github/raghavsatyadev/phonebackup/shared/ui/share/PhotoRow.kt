package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.raghavsatyadev.phonebackup.shared.model.DateSource
import io.github.raghavsatyadev.phonebackup.shared.model.PhotoItem
import io.github.raghavsatyadev.phonebackup.shared.model.UploadStatus
import io.github.raghavsatyadev.phonebackup.shared.model.isFinished
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.edit_date
import io.github.raghavsatyadev.phonebackup.shared.resources.remove_photo
import io.github.raghavsatyadev.phonebackup.shared.resources.source_exif
import io.github.raghavsatyadev.phonebackup.shared.resources.source_filename
import io.github.raghavsatyadev.phonebackup.shared.resources.source_filetime
import io.github.raghavsatyadev.phonebackup.shared.resources.source_manual
import io.github.raghavsatyadev.phonebackup.shared.resources.source_mediastore
import io.github.raghavsatyadev.phonebackup.shared.resources.source_unknown
import io.github.raghavsatyadev.phonebackup.shared.resources.status_done
import io.github.raghavsatyadev.phonebackup.shared.resources.status_failed
import io.github.raghavsatyadev.phonebackup.shared.resources.status_pending
import io.github.raghavsatyadev.phonebackup.shared.resources.status_skipped
import io.github.raghavsatyadev.phonebackup.shared.resources.status_uploading
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun PhotoRow(
  photo: PhotoItem,
  progress: Float,
  onEditDate: () -> Unit,
  onRemove: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val editable = !photo.isFinished && photo.status != UploadStatus.Uploading
  Card(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (photo.isFinished) {
        // The local copy is deleted after upload, so there is nothing left to preview.
        Text(
          text = "✓",
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(64.dp).padding(16.dp),
        )
      } else {
        AsyncImage(
          model = photo.path,
          contentDescription = photo.fileName,
          contentScale = ContentScale.Crop,
          modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(photo.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(
          text = "${photo.takenAt.display()} · ${stringResource(photo.dateSource.label())}",
          style = MaterialTheme.typography.bodySmall,
          color =
            if (photo.dateSource == DateSource.Unknown) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = if (editable) Modifier.clickable(onClick = onEditDate) else Modifier,
        )
        StatusLine(photo)
      }
      when {
        photo.status == UploadStatus.Uploading ->
          CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(32.dp))
        editable ->
          Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = onEditDate) { Text(stringResource(Res.string.edit_date)) }
            TextButton(onClick = onRemove) { Text(stringResource(Res.string.remove_photo)) }
          }
      }
    }
  }
}

@Composable
private fun StatusLine(photo: PhotoItem) {
  val text =
    when (photo.status) {
      UploadStatus.Pending -> stringResource(Res.string.status_pending)
      UploadStatus.Uploading -> stringResource(Res.string.status_uploading)
      UploadStatus.Done -> stringResource(Res.string.status_done)
      UploadStatus.Skipped -> stringResource(Res.string.status_skipped)
      UploadStatus.Failed -> stringResource(Res.string.status_failed, photo.error.orEmpty())
    }
  val color =
    when (photo.status) {
      UploadStatus.Failed -> MaterialTheme.colorScheme.error
      UploadStatus.Done, UploadStatus.Skipped -> MaterialTheme.colorScheme.primary
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private fun DateSource.label(): StringResource =
  when (this) {
    DateSource.Exif -> Res.string.source_exif
    DateSource.MediaStore -> Res.string.source_mediastore
    DateSource.FileName -> Res.string.source_filename
    DateSource.FileTime -> Res.string.source_filetime
    DateSource.Manual -> Res.string.source_manual
    DateSource.Unknown -> Res.string.source_unknown
  }
