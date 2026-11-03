@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.raghavsatyadev.phonebackup.shared.model.UploadStatus
import io.github.raghavsatyadev.phonebackup.shared.model.isFinished
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_open_settings
import io.github.raghavsatyadev.phonebackup.shared.resources.action_pick_photos
import io.github.raghavsatyadev.phonebackup.shared.resources.action_settings
import io.github.raghavsatyadev.phonebackup.shared.resources.app_title
import io.github.raghavsatyadev.phonebackup.shared.resources.empty_body
import io.github.raghavsatyadev.phonebackup.shared.resources.empty_title
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_add_photo_alternate
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_photo_library
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_settings
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_wifi
import io.github.raghavsatyadev.phonebackup.shared.resources.msg_upload_done
import io.github.raghavsatyadev.phonebackup.shared.resources.setup_needed
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.DriftingShape
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ShareRoute(viewModel: ShareViewModel, onPickPhotos: () -> Unit, onOpenSettings: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val haptics = LocalHapticFeedback.current
  LaunchedEffect(viewModel) {
    viewModel.messages.collect {
      val ok = it.text == Res.string.msg_upload_done
      haptics.performHapticFeedback(if (ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
      snackbar.showSnackbar(getString(it.text, it.arg))
    }
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
        actions = {
          IconButton(onClick = onOpenSettings, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_settings), stringResource(Res.string.action_settings))
          }
        },
      )
    },
    modifier = Modifier.imePadding(),
    snackbarHost = { SnackbarHost(snackbar) },
    bottomBar = { if (state.photos.isNotEmpty()) UploadBar(state, onEvent) },
  ) { padding ->
    // Photos are the point of the screen, so they get big tiles: two across on a phone, more on wider screens.
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 150.dp),
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!state.serverConfigured) {
        item(key = "setup", span = { GridItemSpan(maxLineSpan) }) { SetupCard(onOpenSettings) }
      }
      if (state.photos.isEmpty()) {
        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyState(onPickPhotos) }
      } else {
        item(key = "form", span = { GridItemSpan(maxLineSpan) }) { GroupForm(state, onEvent, onPickPhotos) }
        items(state.photos, key = { it.id }) { photo ->
          PhotoTile(
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
  Card(
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
  ) {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(
        painterResource(Res.drawable.ic_wifi),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onTertiaryContainer,
      )
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          stringResource(Res.string.setup_needed),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Button(onClick = onOpenSettings, shapes = ButtonDefaults.shapes()) {
          Text(stringResource(Res.string.action_open_settings))
        }
      }
    }
  }
}

@Composable
private fun EmptyState(onPickPhotos: () -> Unit) {
  val appear = remember { MutableTransitionState(false).apply { targetState = true } }
  AnimatedVisibility(
    visibleState = appear,
    enter =
      fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
        scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), initialScale = 0.92f),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      DriftingShape {
        Icon(
          painterResource(Res.drawable.ic_photo_library),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(56.dp),
        )
      }
      Text(stringResource(Res.string.empty_title), style = MaterialTheme.typography.headlineSmall)
      Text(
        stringResource(Res.string.empty_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
      FilledTonalButton(onClick = onPickPhotos, shapes = ButtonDefaults.shapes()) {
        Icon(painterResource(Res.drawable.ic_add_photo_alternate), null, Modifier.size(ButtonDefaults.IconSize))
        Text(stringResource(Res.string.action_pick_photos), Modifier.padding(start = ButtonDefaults.IconSpacing))
      }
    }
  }
}
