@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.raghavsatyadev.phonebackup.shared.model.UploadStatus
import io.github.raghavsatyadev.phonebackup.shared.model.isFinished
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_add_more
import io.github.raghavsatyadev.phonebackup.shared.resources.action_add_tag
import io.github.raghavsatyadev.phonebackup.shared.resources.action_clear_finished
import io.github.raghavsatyadev.phonebackup.shared.resources.action_retry_failed
import io.github.raghavsatyadev.phonebackup.shared.resources.action_set_date_all
import io.github.raghavsatyadev.phonebackup.shared.resources.action_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.action_cancel_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_close
import io.github.raghavsatyadev.phonebackup.shared.resources.album_label
import io.github.raghavsatyadev.phonebackup.shared.resources.album_placeholder
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_add_photo_alternate
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_check_circle
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_close
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_cloud_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_event
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_folder
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_refresh
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_sell
import io.github.raghavsatyadev.phonebackup.shared.resources.photo_count
import io.github.raghavsatyadev.phonebackup.shared.resources.remove_tag
import io.github.raghavsatyadev.phonebackup.shared.resources.tags_label
import io.github.raghavsatyadev.phonebackup.shared.resources.tags_placeholder
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.animationsEnabled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Album, tags and group actions for the photos being shared. */
@Composable
fun GroupForm(state: ShareUiState, onEvent: (ShareEvent) -> Unit, onPickPhotos: () -> Unit) {
  val locked = state.isUploading
  Card(
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedTextField(
        value = state.album,
        onValueChange = { onEvent(ShareEvent.AlbumChanged(it)) },
        label = { Text(stringResource(Res.string.album_label)) },
        placeholder = { Text(stringResource(Res.string.album_placeholder)) },
        leadingIcon = { Icon(painterResource(Res.drawable.ic_folder), null) },
        singleLine = true,
        enabled = !locked,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
      )
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = state.tagInput,
          onValueChange = { onEvent(ShareEvent.TagInputChanged(it)) },
          label = { Text(stringResource(Res.string.tags_label)) },
          placeholder = { Text(stringResource(Res.string.tags_placeholder)) },
          leadingIcon = { Icon(painterResource(Res.drawable.ic_sell), null) },
          singleLine = true,
          enabled = !locked,
          shape = MaterialTheme.shapes.medium,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { onEvent(ShareEvent.AddTag) }),
          modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
          onClick = { onEvent(ShareEvent.AddTag) },
          enabled = !locked && state.tagInput.isNotBlank(),
          shapes = ButtonDefaults.shapes(),
        ) {
          Text(stringResource(Res.string.action_add_tag))
        }
      }
      if (state.tags.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          state.tags.forEach { tag ->
            val remove = stringResource(Res.string.remove_tag, tag)
            InputChip(
              selected = false,
              onClick = { onEvent(ShareEvent.RemoveTag(tag)) },
              enabled = !locked,
              label = { Text(tag) },
              trailingIcon = {
                Icon(painterResource(Res.drawable.ic_close), null, Modifier.size(InputChipDefaults.IconSize))
              },
              modifier = Modifier.semantics { contentDescription = remove },
            )
          }
        }
      }
      Text(
        stringResource(Res.string.photo_count, state.photos.size.toString()),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TonalAction(Res.drawable.ic_add_photo_alternate, Res.string.action_add_more, enabled = !locked, onClick = onPickPhotos)
        TonalAction(
          Res.drawable.ic_event,
          Res.string.action_set_date_all,
          enabled = !locked && state.photos.any { !it.isFinished },
          onClick = { onEvent(ShareEvent.EditDate(DateTarget.All)) },
        )
      }
    }
  }
}

@Composable
private fun TonalAction(
  icon: DrawableResource,
  label: org.jetbrains.compose.resources.StringResource,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  FilledTonalButton(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes()) {
    Icon(painterResource(icon), null, Modifier.size(ButtonDefaults.IconSize))
    Text(stringResource(label), Modifier.padding(start = ButtonDefaults.IconSpacing))
  }
}
