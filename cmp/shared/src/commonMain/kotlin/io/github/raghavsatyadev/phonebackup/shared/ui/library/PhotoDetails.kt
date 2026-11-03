@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.raghavsatyadev.phonebackup.shared.data.GalleryPhoto
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.ai_tags
import io.github.raghavsatyadev.phonebackup.shared.resources.best_shot
import io.github.raghavsatyadev.phonebackup.shared.resources.similar_shots
import io.github.raghavsatyadev.phonebackup.shared.resources.text_in_photo
import io.github.raghavsatyadev.phonebackup.shared.resources.video
import io.github.raghavsatyadev.phonebackup.shared.resources.your_tags
import org.jetbrains.compose.resources.stringResource

/** Date, place, caption, tags and similar shots of one photo. */
@Composable
internal fun PhotoDetails(
  state: LibraryUiState,
  photo: GalleryPhoto,
  viewer: ViewerState,
  onEvent: (LibraryEvent) -> Unit,
  modifier: Modifier,
) {
  Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(formatTakenAt(photo.takenAt), style = MaterialTheme.typography.titleMedium)
    val where = listOf(photo.album, photo.category, photo.event).filter { it.isNotBlank() }.distinct()
    val kind = if (photo.isVideo) stringResource(Res.string.video) + " · " else ""
    Text(
      kind + where.joinToString(" · "),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (photo.caption.isNotBlank()) Text(photo.caption, style = MaterialTheme.typography.bodyLarge)
    TagSection(stringResource(Res.string.your_tags), photo.tags)
    TagSection(stringResource(Res.string.ai_tags), photo.aiTags)
    if (photo.text.isNotBlank()) {
      Label(stringResource(Res.string.text_in_photo))
      Text(photo.text, style = MaterialTheme.typography.bodyMedium)
    }
    val similar = state.similarTo(photo.id)
    if (similar.size > 1) {
      Label(stringResource(Res.string.similar_shots) + " (${similar.size})")
      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(similar, key = { it.id }) { s ->
          val selected = s.id == photo.id
          Column {
            Thumb(
              state.config,
              s,
              Modifier.size(88.dp)
                .aspectRatio(1f)
                .then(
                  if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), MaterialTheme.shapes.small)
                  else Modifier
                )
                .clickable(enabled = !selected || viewer.ids.size != similar.size) {
                  onEvent(LibraryEvent.OpenSimilar(s.id))
                },
            )
            if (s.isBest) {
              Text(
                stringResource(Res.string.best_shot),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TagSection(title: String, tags: List<String>) {
  if (tags.isEmpty()) return
  Label(title)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    tags.forEach { SuggestionChip(onClick = {}, label = { Text(it) }) }
  }
}

@Composable
private fun Label(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}
