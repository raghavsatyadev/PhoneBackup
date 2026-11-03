@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_check
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_check_circle
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_cloud_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_close
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
import io.github.raghavsatyadev.phonebackup.shared.resources.status_skipped
import io.github.raghavsatyadev.phonebackup.shared.resources.status_uploading
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * One shared photo as a grid tile: the photo fills a square, status and remove sit on top of it,
 * and the date (tap to edit) is a small caption below.
 */
@Composable
fun PhotoTile(
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
    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
      Preview(photo)
      if (photo.status == UploadStatus.Uploading) {
        Box(
          Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
          contentAlignment = Alignment.Center,
        ) {
          CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(56.dp))
        }
      }
      StatusPill(photo.status, Modifier.align(Alignment.TopStart).padding(8.dp))
      if (editable) {
        FilledTonalIconButton(
          onClick = onRemove,
          shapes = IconButtonDefaults.shapes(),
          modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        ) {
          Icon(painterResource(Res.drawable.ic_close), stringResource(Res.string.remove_photo))
        }
      }
    }
    DateCaption(photo, editable, onEditDate)
    if (photo.status == UploadStatus.Failed && !photo.error.isNullOrBlank()) {
      Text(
        photo.error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
      )
    }
  }
}

@Composable
private fun Preview(photo: PhotoItem) {
  if (photo.isFinished) {
    // The local copy is deleted after upload, so a badge stands in for the photo.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
      Box(
        Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          painterResource(Res.drawable.ic_check),
          contentDescription = photo.fileName,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(40.dp),
        )
      }
    }
  } else {
    AsyncImage(
      model = photo.path,
      contentDescription = photo.fileName,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

@Composable
private fun DateCaption(photo: PhotoItem, editable: Boolean, onEditDate: () -> Unit) {
  val color =
    if (photo.dateSource == DateSource.Unknown) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant
  val edit = stringResource(Res.string.edit_date)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    modifier =
      Modifier.fillMaxWidth()
        .then(if (editable) Modifier.clickable(onClickLabel = edit, onClick = onEditDate) else Modifier)
        .heightIn(min = 48.dp)
        .padding(horizontal = 12.dp, vertical = 6.dp),
  ) {
    Icon(painterResource(Res.drawable.ic_event), null, tint = color, modifier = Modifier.size(16.dp))
    Column {
      Text(
        photo.takenAt.display(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        stringResource(photo.dateSource.label()),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private data class PillStyle(val text: StringResource, val icon: DrawableResource, val container: Color, val content: Color)

/** Shown only once something has happened; a photo waiting to upload needs no label. */
@Composable
private fun StatusPill(status: UploadStatus, modifier: Modifier) {
  val c = MaterialTheme.colorScheme
  val style =
    when (status) {
      UploadStatus.Pending -> return
      UploadStatus.Uploading ->
        PillStyle(Res.string.status_uploading, Res.drawable.ic_cloud_upload, c.secondaryContainer, c.onSecondaryContainer)
      UploadStatus.Done -> PillStyle(Res.string.status_done, Res.drawable.ic_check_circle, c.primaryContainer, c.onPrimaryContainer)
      UploadStatus.Skipped ->
        PillStyle(Res.string.status_skipped, Res.drawable.ic_check_circle, c.tertiaryContainer, c.onTertiaryContainer)
      UploadStatus.Failed -> PillStyle(Res.string.status_failed_short, Res.drawable.ic_error, c.errorContainer, c.onErrorContainer)
    }
  Surface(shape = MaterialTheme.shapes.small, color = style.container, contentColor = style.content, modifier = modifier) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(painterResource(style.icon), null, modifier = Modifier.size(14.dp))
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
