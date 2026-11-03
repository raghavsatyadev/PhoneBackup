package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.raghavsatyadev.phonebackup.shared.model.UploadStatus
import io.github.raghavsatyadev.phonebackup.shared.model.isFinished
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_add_more
import io.github.raghavsatyadev.phonebackup.shared.resources.action_add_tag
import io.github.raghavsatyadev.phonebackup.shared.resources.action_clear_finished
import io.github.raghavsatyadev.phonebackup.shared.resources.action_open_settings
import io.github.raghavsatyadev.phonebackup.shared.resources.action_pick_photos
import io.github.raghavsatyadev.phonebackup.shared.resources.action_retry_failed
import io.github.raghavsatyadev.phonebackup.shared.resources.action_set_date_all
import io.github.raghavsatyadev.phonebackup.shared.resources.action_settings
import io.github.raghavsatyadev.phonebackup.shared.resources.action_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.action_uploading
import io.github.raghavsatyadev.phonebackup.shared.resources.album_label
import io.github.raghavsatyadev.phonebackup.shared.resources.album_placeholder
import io.github.raghavsatyadev.phonebackup.shared.resources.app_title
import io.github.raghavsatyadev.phonebackup.shared.resources.empty_body
import io.github.raghavsatyadev.phonebackup.shared.resources.empty_title
import io.github.raghavsatyadev.phonebackup.shared.resources.photo_count
import io.github.raghavsatyadev.phonebackup.shared.resources.remove_tag
import io.github.raghavsatyadev.phonebackup.shared.resources.setup_needed
import io.github.raghavsatyadev.phonebackup.shared.resources.tags_label
import io.github.raghavsatyadev.phonebackup.shared.resources.tags_placeholder
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun ShareRoute(viewModel: ShareViewModel, onPickPhotos: () -> Unit, onOpenSettings: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(viewModel) {
    viewModel.messages.collect { snackbar.showSnackbar(getString(it.text, it.arg)) }
  }
  ShareScreen(state, viewModel::onEvent, onPickPhotos, onOpenSettings, snackbar)
  val target = state.dateTarget
  if (target != null) {
    val initial =
      when (target) {
        is DateTarget.One -> state.photos.firstOrNull { it.id == target.id }?.takenAt
        DateTarget.All -> state.photos.firstOrNull { !it.isFinished }?.takenAt
      }
    if (initial != null) {
      DateTimeDialog(
        initial = initial,
        onDismiss = { viewModel.onEvent(ShareEvent.DismissDateEdit) },
        onConfirm = { viewModel.onEvent(ShareEvent.DateChosen(it)) },
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
  state: ShareUiState,
  onEvent: (ShareEvent) -> Unit,
  onPickPhotos: () -> Unit,
  onOpenSettings: () -> Unit,
  snackbar: SnackbarHostState,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.app_title)) },
        actions = { TextButton(onClick = onOpenSettings) { Text(stringResource(Res.string.action_settings)) } },
      )
    },
    modifier = Modifier.imePadding(),
    snackbarHost = { SnackbarHost(snackbar) },
    bottomBar = { if (state.photos.isNotEmpty()) UploadBar(state, onEvent) },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!state.serverConfigured) {
        item(key = "setup") { SetupCard(onOpenSettings) }
      }
      if (state.photos.isEmpty()) {
        item(key = "empty") { EmptyState(onPickPhotos) }
      } else {
        item(key = "form") { GroupForm(state, onEvent, onPickPhotos) }
        items(state.photos, key = { it.id }) { photo ->
          PhotoRow(
            photo = photo,
            progress = if (photo.status == UploadStatus.Uploading) state.currentProgress else 0f,
            onEditDate = { onEvent(ShareEvent.EditDate(DateTarget.One(photo.id))) },
            onRemove = { onEvent(ShareEvent.RemovePhoto(photo.id)) },
            modifier = Modifier.animateItem(),
          )
        }
      }
    }
  }
}

@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(Res.string.setup_needed), color = MaterialTheme.colorScheme.onErrorContainer)
      Button(onClick = onOpenSettings) { Text(stringResource(Res.string.action_open_settings)) }
    }
  }
}

@Composable
private fun EmptyState(onPickPhotos: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(stringResource(Res.string.empty_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(Res.string.empty_body), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onPickPhotos) { Text(stringResource(Res.string.action_pick_photos)) }
  }
}

@Composable
private fun GroupForm(state: ShareUiState, onEvent: (ShareEvent) -> Unit, onPickPhotos: () -> Unit) {
  val locked = state.isUploading
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedTextField(
      value = state.album,
      onValueChange = { onEvent(ShareEvent.AlbumChanged(it)) },
      label = { Text(stringResource(Res.string.album_label)) },
      placeholder = { Text(stringResource(Res.string.album_placeholder)) },
      singleLine = true,
      enabled = !locked,
      modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = state.tagInput,
        onValueChange = { onEvent(ShareEvent.TagInputChanged(it)) },
        label = { Text(stringResource(Res.string.tags_label)) },
        placeholder = { Text(stringResource(Res.string.tags_placeholder)) },
        singleLine = true,
        enabled = !locked,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onEvent(ShareEvent.AddTag) }),
        modifier = Modifier.weight(1f),
      )
      OutlinedButton(onClick = { onEvent(ShareEvent.AddTag) }, enabled = !locked && state.tagInput.isNotBlank()) {
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
            trailingIcon = { Text("×") },
            modifier = Modifier.semantics { contentDescription = remove },
          )
        }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(stringResource(Res.string.photo_count, state.photos.size.toString()), style = MaterialTheme.typography.titleSmall)
      Row {
        TextButton(onClick = onPickPhotos, enabled = !locked) { Text(stringResource(Res.string.action_add_more)) }
        TextButton(
          onClick = { onEvent(ShareEvent.EditDate(DateTarget.All)) },
          enabled = !locked && state.photos.any { !it.isFinished },
        ) {
          Text(stringResource(Res.string.action_set_date_all))
        }
      }
    }
  }
}

@Composable
private fun UploadBar(state: ShareUiState, onEvent: (ShareEvent) -> Unit) {
  Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (state.isUploading) {
      val done = state.photos.count { it.isFinished || it.status == UploadStatus.Failed }
      LinearProgressIndicator(
        progress = { (done + state.currentProgress) / state.photos.size.coerceAtLeast(1) },
        modifier = Modifier.fillMaxWidth(),
      )
    }
    Box(Modifier.fillMaxWidth()) {
      if (state.photos.isNotEmpty() && state.photos.all { it.isFinished }) {
        OutlinedButton(onClick = { onEvent(ShareEvent.ClearFinished) }, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(Res.string.action_clear_finished))
        }
      } else {
        val retry = state.photos.any { it.status == UploadStatus.Failed }
        Button(onClick = { onEvent(ShareEvent.Upload) }, enabled = state.canUpload, modifier = Modifier.fillMaxWidth()) {
          val label =
            when {
              state.isUploading -> Res.string.action_uploading
              retry -> Res.string.action_retry_failed
              else -> Res.string.action_upload
            }
          Text(stringResource(label))
        }
      }
    }
  }
}
