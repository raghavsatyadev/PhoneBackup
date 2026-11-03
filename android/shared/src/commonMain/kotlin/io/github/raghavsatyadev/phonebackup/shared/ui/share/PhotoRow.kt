@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.raghavsatyadev.phonebackup.shared.model.DateSource
import io.github.raghavsatyadev.phonebackup.shared.model.PhotoItem
import io.github.raghavsatyadev.phonebackup.shared.model.UploadStatus
import io.github.raghavsatyadev.phonebackup.shared.model.isFinished
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.edit_date
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_check
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_check_circle
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_cloud_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_delete
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_error
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_event
import io.github.raghavsatyadev.phonebackup.shared.resources.remove_photo
import io.github.raghavsatyadev.phonebackup.shared.resources.source_exif
import io.github.raghavsatyadev.phonebackup.shared.resources.source_filename
import io.github.raghavsatyadev.phonebackup.shared.resources.source_filetime
import io.github.raghavsatyadev.phonebackup.shared.resources.source_manual
import io.github.raghavsatyadev.phonebackup.shared.resources.source_mediastore
import io.github.raghavsatyadev.phonebackup.shared.resources.source_unknown
import io.github.raghavsatyadev.phonebackup.shared.resources.status_done
import io.github.raghavsatyadev.phonebackup.shared.resources.status_failed_short
import io.github.raghavsatyadev.phonebackup.shared.resources.status_pending
import io.github.raghavsatyadev.phonebackup.shared.resources.status_skipped
import io.github.raghavsatyadev.phonebackup.shared.resources.status_uploading
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
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
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Thumbnail(photo)
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          photo.fileName,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        DateLine(photo, editable, onEditDate)
        StatusPill(photo.status)
        if (photo.status == UploadStatus.Failed && !photo.error.isNullOrBlank()) {
          Text(
            photo.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      when {
        photo.status == UploadStatus.Uploading ->
          CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(40.dp))
        editable ->
          Column {
            IconButton(onClick = onEditDate, shapes = IconButtonDefaults.shapes()) {
              Icon(painterResource(Res.drawable.ic_event), stringResource(Res.string.edit_date))
            }
            IconButton(onClick = onRemove, shapes = IconButtonDefaults.shapes()) {
              Icon(painterResource(Res.drawable.ic_delete), stringResource(Res.string.remove_photo))
            }
          }
      }
    }
  }
}

@Composable
private fun Thumbnail(photo: PhotoItem) {
  if (photo.isFinished) {
    // The local copy is deleted after upload, so a badge stands in for the preview.
    val shape = MaterialShapes.Cookie9Sided.toShape()
    Box(
      Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, shape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painterResource(Res.drawable.ic_check),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(32.dp),
      )
    }
  } else {
    AsyncImage(
      model = photo.path,
      contentDescription = photo.fileName,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium),
    )
  }
}

@Composable
private fun DateLine(photo: PhotoItem, editable: Boolean, onEditDate: () -> Unit) {
  val color =
    if (photo.dateSource == DateSource.Unknown) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    modifier = if (editable) Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onEditDate) else Modifier,
  ) {
    Icon(painterResource(Res.drawable.ic_event), null, tint = color, modifier = Modifier.size(16.dp))
    Text(
      text = "${photo.takenAt.display()} · ${stringResource(photo.dateSource.label())}",
      style = MaterialTheme.typography.bodySmall,
      color = color,
    )
  }
}

private data class PillStyle(val text: StringResource, val icon: DrawableResource?, val container: Color, val content: Color)

@Composable
private fun StatusPill(status: UploadStatus) {
  val c = MaterialTheme.colorScheme
  val style =
    when (status) {
      UploadStatus.Pending -> PillStyle(Res.string.status_pending, null, c.surfaceContainerHighest, c.onSurfaceVariant)
      UploadStatus.Uploading ->
        PillStyle(Res.string.status_uploading, Res.drawable.ic_cloud_upload, c.secondaryContainer, c.onSecondaryContainer)
      UploadStatus.Done -> PillStyle(Res.string.status_done, Res.drawable.ic_check_circle, c.primaryContainer, c.onPrimaryContainer)
      UploadStatus.Skipped ->
        PillStyle(Res.string.status_skipped, Res.drawable.ic_check_circle, c.tertiaryContainer, c.onTertiaryContainer)
      UploadStatus.Failed -> PillStyle(Res.string.status_failed_short, Res.drawable.ic_error, c.errorContainer, c.onErrorContainer)
    }
  Surface(shape = MaterialTheme.shapes.small, color = style.container, contentColor = style.content) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (style.icon != null) Icon(painterResource(style.icon), null, modifier = Modifier.size(14.dp))
      Text(stringResource(style.text), style = MaterialTheme.typography.labelMedium)
    }
  }
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
