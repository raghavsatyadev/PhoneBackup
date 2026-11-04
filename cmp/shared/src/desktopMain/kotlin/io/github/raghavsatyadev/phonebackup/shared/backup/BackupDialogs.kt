@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.raghavsatyadev.phonebackup.shared.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Connects to a phone with wireless debugging on (address from its Developer options). */
@Composable
internal fun WifiDialog(state: BackupUiState, onEvent: (BackupEvent) -> Unit) {
  AlertDialog(
    onDismissRequest = { onEvent(BackupEvent.CloseWifi) },
    icon = { Icon(painterResource(Res.drawable.ic_wifi), null) },
    title = { Text(stringResource(Res.string.backup_wifi_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.backup_wifi_body))
        OutlinedTextField(
          value = state.wifiAddress,
          onValueChange = { onEvent(BackupEvent.WifiAddress(it)) },
          label = { Text(stringResource(Res.string.backup_wifi_label)) },
          placeholder = { Text("192.168.1.20:5555") },
          singleLine = true,
          isError = state.wifiError != null,
          supportingText = state.wifiError?.let { { Text(it) } },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { onEvent(BackupEvent.ConnectWifi) }),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onEvent(BackupEvent.ConnectWifi) },
        enabled = state.wifiAddress.isNotBlank() && !state.wifiBusy,
        shapes = ButtonDefaults.shapes(),
      ) {
        Text(stringResource(if (state.wifiBusy) Res.string.backup_wifi_connecting else Res.string.backup_wifi_connect))
      }
    },
    dismissButton = {
      TextButton(onClick = { onEvent(BackupEvent.CloseWifi) }, shapes = ButtonDefaults.shapes()) {
        Text(stringResource(Res.string.action_cancel))
      }
    },
  )
}

/** Resuming a backup with a different phone than the one that started it. */
@Composable
internal fun MismatchDialog(mismatch: DeviceMismatch, onEvent: (BackupEvent) -> Unit) {
  AlertDialog(
    onDismissRequest = { onEvent(BackupEvent.DismissMismatch) },
    icon = { Icon(painterResource(Res.drawable.ic_smartphone), null) },
    title = { Text(stringResource(Res.string.backup_mismatch_title)) },
    text = { Text(stringResource(Res.string.backup_mismatch_body, mismatch.oldLabel, mismatch.newLabel)) },
    confirmButton = {
      TextButton(onClick = { onEvent(BackupEvent.ConfirmMismatch) }, shapes = ButtonDefaults.shapes()) {
        Text(stringResource(Res.string.backup_mismatch_confirm, mismatch.newLabel))
      }
    },
    dismissButton = {
      TextButton(onClick = { onEvent(BackupEvent.DismissMismatch) }, shapes = ButtonDefaults.shapes()) {
        Text(stringResource(Res.string.action_cancel))
      }
    },
  )
}

/**
 * Wraps [actions] so Cancel asks first. Returns the wrapped actions; the dialog shows itself
 * while a cancel is waiting for an answer.
 */
@Composable
internal fun rememberConfirmingActions(actions: TransferActions): TransferActions {
  var pending by remember { mutableStateOf<String?>(null) }
  pending?.let { id ->
    AlertDialog(
      onDismissRequest = { pending = null },
      icon = { Icon(painterResource(Res.drawable.ic_close), null) },
      title = { Text(stringResource(Res.string.backup_cancel_title)) },
      text = { Text(stringResource(Res.string.backup_cancel_body)) },
      confirmButton = {
        TextButton(
          onClick = {
            pending = null
            actions.onCancel(id)
          },
          shapes = ButtonDefaults.shapes(),
        ) {
          Text(stringResource(Res.string.backup_cancel), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { pending = null }, shapes = ButtonDefaults.shapes()) {
          Text(stringResource(Res.string.backup_keep_going))
        }
      },
    )
  }
  return remember(actions) { TransferActions(actions.onPause, actions.onResume, { pending = it }, actions.onRetry) }
}
