@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.raghavsatyadev.phonebackup.shared.backup.data.Transfer
import io.github.raghavsatyadev.phonebackup.shared.resources.*
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.DelayedLoadingIndicator
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SetupCard(title: StringResource, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
  ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
      }
      content()
    }
  }
}

@Composable
internal fun DeviceCard(state: BackupUiState, onEvent: (BackupEvent) -> Unit) {
  SetupCard(
    Res.string.backup_phone_title,
    action = {
      IconButton(onClick = { onEvent(BackupEvent.RefreshDevices) }, shapes = IconButtonDefaults.shapes()) {
        Icon(painterResource(Res.drawable.ic_refresh), stringResource(Res.string.backup_reload))
      }
    },
  ) {
    when {
      !state.devicesLoaded -> DelayedLoadingIndicator(Modifier.size(40.dp))
      state.devices.isEmpty() -> Hint(stringResource(Res.string.backup_no_phone))
    }
    state.devices.forEach { device ->
      ListItem(
        headlineContent = { Text(device.label) },
        supportingContent = {
          Text(
            stringResource(if (device.isWifi) Res.string.backup_phone_wifi else Res.string.backup_phone_usb, device.serial)
          )
        },
        leadingContent = { RadioButton(selected = device.serial == state.selectedSerial, onClick = null) },
        trailingContent =
          if (device.isWifi) {
            {
              TextButton(onClick = { onEvent(BackupEvent.DisconnectWifi(device.serial)) }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.backup_disconnect))
              }
            }
          } else null,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier =
          Modifier.selectable(
            selected = device.serial == state.selectedSerial,
            role = Role.RadioButton,
            onClick = { onEvent(BackupEvent.SelectDevice(device.serial)) },
          ),
      )
    }
    OutlinedButton(onClick = { onEvent(BackupEvent.OpenWifi) }, shapes = ButtonDefaults.shapes()) {
      Icon(painterResource(Res.drawable.ic_wifi), null, Modifier.size(ButtonDefaults.IconSize))
      Spacer(Modifier.width(ButtonDefaults.IconSpacing))
      Text(stringResource(Res.string.backup_connect_wifi))
    }
  }
}

@Composable
internal fun SourcesCard(state: BackupUiState, onEvent: (BackupEvent) -> Unit, onBrowse: (String) -> Unit) {
  SetupCard(
    Res.string.backup_sources_title,
    action = {
      if (state.sources.isNotEmpty()) {
        TextButton(onClick = { onEvent(BackupEvent.ClearSources) }, shapes = ButtonDefaults.shapes()) {
          Text(stringResource(Res.string.backup_clear))
        }
      }
    },
  ) {
    if (state.sources.isEmpty()) Hint(stringResource(Res.string.backup_sources_empty))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      state.sources.forEach { path ->
        InputChip(
          selected = true,
          onClick = { onEvent(BackupEvent.RemoveSource(path)) },
          label = { Text(path.removePrefix("/storage/emulated/0")) },
          trailingIcon = {
            Icon(painterResource(Res.drawable.ic_close), stringResource(Res.string.backup_remove), Modifier.size(InputChipDefaults.IconSize))
          },
        )
      }
    }
    Text(stringResource(Res.string.backup_quick_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      QuickSource.entries.forEach { quick ->
        AssistChip(
          onClick = { onEvent(BackupEvent.AddSources(listOf(quick.path))) },
          label = { Text(stringResource(quick.label)) },
          enabled = quick.path !in state.sources,
        )
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = state.pathDraft,
        onValueChange = { onEvent(BackupEvent.PathDraft(it)) },
        label = { Text(stringResource(Res.string.backup_path_label)) },
        placeholder = { Text("/storage/emulated/0/Recordings") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onEvent(BackupEvent.AddDraft) }),
        modifier = Modifier.weight(1f),
      )
      FilledTonalButton(onClick = { onEvent(BackupEvent.AddDraft) }, enabled = state.pathDraft.isNotBlank(), shapes = ButtonDefaults.shapes()) {
        Text(stringResource(Res.string.backup_add))
      }
    }
    val serial = state.selectedSerial
    OutlinedButton(onClick = { serial?.let(onBrowse) }, enabled = serial != null, shapes = ButtonDefaults.shapes()) {
      Icon(painterResource(Res.drawable.ic_folder), null, Modifier.size(ButtonDefaults.IconSize))
      Spacer(Modifier.width(ButtonDefaults.IconSpacing))
      Text(stringResource(Res.string.backup_browse_phone))
    }
  }
}

@Composable
internal fun FilterCard(state: BackupUiState, onEvent: (BackupEvent) -> Unit) {
  SetupCard(Res.string.backup_filter_title) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterPreset.entries.forEach { preset ->
        FilterChip(
          selected = state.filter.trim() == preset.patterns,
          onClick = { onEvent(BackupEvent.Filter(preset.patterns)) },
          label = { Text(stringResource(preset.label)) },
        )
      }
    }
    OutlinedTextField(
      value = state.filter,
      onValueChange = { onEvent(BackupEvent.Filter(it)) },
      label = { Text(stringResource(Res.string.backup_filter_label)) },
      supportingText = { Text(stringResource(Res.string.backup_filter_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
internal fun DestinationCard(state: BackupUiState, canBrowse: Boolean, onEvent: (BackupEvent) -> Unit) {
  SetupCard(Res.string.backup_destination_title) {
    OutlinedTextField(
      value = state.destination,
      onValueChange = { onEvent(BackupEvent.Destination(it)) },
      label = { Text(stringResource(Res.string.backup_destination_label)) },
      placeholder = { Text("D:\\PhoneBackup\\Phone") },
      singleLine = true,
      isError = state.destinationError != null,
      supportingText = state.destinationError?.let { { Text(it) } },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { onEvent(BackupEvent.CheckDestination) }),
      modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (canBrowse) {
        OutlinedButton(onClick = { onEvent(BackupEvent.BrowseDestination) }, shapes = ButtonDefaults.shapes()) {
          Icon(painterResource(Res.drawable.ic_folder), null, Modifier.size(ButtonDefaults.IconSize))
          Spacer(Modifier.width(ButtonDefaults.IconSpacing))
          Text(stringResource(Res.string.backup_browse_pc))
        }
      }
      TextButton(
        onClick = { onEvent(BackupEvent.CheckDestination) },
        enabled = state.destination.isNotBlank() && !state.checkingDestination,
        shapes = ButtonDefaults.shapes(),
      ) {
        Text(stringResource(Res.string.backup_check_folder))
      }
      if (state.checkingDestination) DelayedLoadingIndicator(Modifier.size(32.dp))
    }
    state.detected?.let { DetectedBackup(it, onEvent) }
  }
}

/** A backup already saved in the chosen folder: offer to carry on with it. */
@Composable
private fun DetectedBackup(transfer: Transfer, onEvent: (BackupEvent) -> Unit) {
  ListItem(
    leadingContent = { Icon(painterResource(Res.drawable.ic_history), null) },
    headlineContent = { Text(stringResource(Res.string.backup_detected_title)) },
    supportingContent = {
      Text(
        stringResource(
          Res.string.backup_detected_body,
          transfer.handledFiles,
          transfer.totalFiles,
          transfer.deviceModel.ifBlank { transfer.activeSerial },
        )
      )
    },
    trailingContent =
      if (transfer.canResume) {
        {
          FilledTonalButton(onClick = { onEvent(BackupEvent.Resume(transfer)) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(Res.string.backup_resume))
          }
        }
      } else null,
    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
internal fun Hint(text: String) {
  Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
