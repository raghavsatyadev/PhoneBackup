@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_browse
import io.github.raghavsatyadev.phonebackup.shared.resources.action_change_folder
import io.github.raghavsatyadev.phonebackup.shared.resources.folder_changed
import io.github.raghavsatyadev.phonebackup.shared.resources.folder_failed
import io.github.raghavsatyadev.phonebackup.shared.resources.folder_intro
import io.github.raghavsatyadev.phonebackup.shared.resources.folder_label
import io.github.raghavsatyadev.phonebackup.shared.resources.folder_remote
import io.github.raghavsatyadev.phonebackup.shared.resources.folder_title
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_folder
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun FolderRoute(viewModel: FolderViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  FolderCard(state, viewModel::onEvent)
}

/** Where the PC saves photos. Editable only when the PC says this app runs on it. */
@Composable
fun FolderCard(state: FolderUiState, onEvent: (FolderEvent) -> Unit) {
  val current = state.current ?: return
  ElevatedCard(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(Res.drawable.ic_folder), null)
        Text(stringResource(Res.string.folder_title), style = MaterialTheme.typography.titleMedium)
      }
      if (!state.canChange) {
        Text(stringResource(Res.string.folder_remote, current), style = MaterialTheme.typography.bodyMedium)
        return@Column
      }
      Text(
        stringResource(Res.string.folder_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = state.draft,
        onValueChange = { onEvent(FolderEvent.DraftChanged(it)) },
        label = { Text(stringResource(Res.string.folder_label)) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onEvent(FolderEvent.Browse) }, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
          Text(stringResource(Res.string.action_browse))
        }
        Button(
          onClick = { onEvent(FolderEvent.Apply) },
          enabled = !state.busy && state.draft.isNotBlank() && state.draft.trim() != current,
          shapes = ButtonDefaults.shapes(),
        ) {
          Text(stringResource(Res.string.action_change_folder))
        }
      }
      when (val m = state.message) {
        is FolderMessage.Changed ->
          Text(stringResource(Res.string.folder_changed, m.path), color = MaterialTheme.colorScheme.primary)
        is FolderMessage.Failed ->
          Text(stringResource(Res.string.folder_failed, m.reason), color = MaterialTheme.colorScheme.error)
        null -> {}
      }
    }
  }
}
