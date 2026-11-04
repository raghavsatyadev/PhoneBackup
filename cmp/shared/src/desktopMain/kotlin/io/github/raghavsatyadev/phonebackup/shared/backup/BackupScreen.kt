@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.raghavsatyadev.phonebackup.shared.backup.data.Transfer
import io.github.raghavsatyadev.phonebackup.shared.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** What the Backup tab opens: the phone folder browser and a backup's detail screen. */
class BackupNav(val onBrowsePhone: (String) -> Unit, val onOpenTransfer: (String) -> Unit)

@Composable
fun BackupRoute(viewModel: BackupViewModel, nav: BackupNav) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(viewModel, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.watchDevices() } }
  LaunchedEffect(viewModel) {
    viewModel.messages.collect { msg ->
      val text = getString(msg.text, msg.detail)
      snackbar.showSnackbar(text)
    }
  }
  val actions = rememberConfirmingActions(rememberTransferActions(viewModel::onEvent))
  BackupScreen(state, viewModel.canBrowse, viewModel.canImport, snackbar, actions, nav, viewModel::onEvent)
}

/** The card buttons, sent to [BackupViewModel] (the detail screen uses the same ones). */
@Composable
fun rememberTransferActions(onEvent: (BackupEvent) -> Unit): TransferActions =
  remember(onEvent) {
    TransferActions(
      onPause = { onEvent(BackupEvent.Pause(it)) },
      onResume = { onEvent(BackupEvent.Resume(it)) },
      onCancel = { onEvent(BackupEvent.Cancel(it)) },
      onRetry = { onEvent(BackupEvent.RetryFailed(it)) },
    )
  }

@Composable
fun BackupScreen(
  state: BackupUiState,
  canBrowse: Boolean,
  canImport: Boolean,
  snackbar: SnackbarHostState,
  actions: TransferActions,
  nav: BackupNav,
  onEvent: (BackupEvent) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.backup_title)) },
        actions = {
          if (canImport) {
            TextButton(onClick = { onEvent(BackupEvent.ImportStatusFile) }, shapes = ButtonDefaults.shapes()) {
              Icon(painterResource(Res.drawable.ic_history), null, Modifier.size(ButtonDefaults.IconSize))
              Spacer(Modifier.width(ButtonDefaults.IconSpacing))
              Text(stringResource(Res.string.backup_import))
            }
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding ->
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
      if (maxWidth >= 1000.dp) {
        // Wide window: the form on the left, the backups on the right, each scrolling alone.
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          LazyColumn(Modifier.weight(1f), contentPadding = LIST_PADDING, verticalArrangement = SPACING) {
            setup(state, canBrowse, nav, onEvent)
          }
          LazyColumn(Modifier.weight(1f), contentPadding = LIST_PADDING, verticalArrangement = SPACING) {
            transfers(state.transfers, actions, nav)
          }
        }
      } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = LIST_PADDING, verticalArrangement = SPACING) {
          setup(state, canBrowse, nav, onEvent)
          transfers(state.transfers, actions, nav)
        }
      }
    }
  }
  if (state.wifiOpen) WifiDialog(state, onEvent)
  state.mismatch?.let { MismatchDialog(it, onEvent) }
}

private val LIST_PADDING = PaddingValues(16.dp)
private val SPACING = Arrangement.spacedBy(12.dp)

private fun LazyListScope.setup(
  state: BackupUiState,
  canBrowse: Boolean,
  nav: BackupNav,
  onEvent: (BackupEvent) -> Unit,
) {
  if (!state.connected) item(key = "offline", contentType = "banner") { OfflineBanner() }
  item(key = "device", contentType = "card") { DeviceCard(state, onEvent) }
  item(key = "sources", contentType = "card") { SourcesCard(state, onEvent, nav.onBrowsePhone) }
  item(key = "filter", contentType = "card") { FilterCard(state, onEvent) }
  item(key = "destination", contentType = "card") { DestinationCard(state, canBrowse, onEvent) }
  item(key = "start", contentType = "start") {
    val height = ButtonDefaults.MediumContainerHeight
    Button(
      onClick = { onEvent(BackupEvent.Start) },
      enabled = state.canStart,
      shapes = ButtonDefaults.shapesFor(height),
      contentPadding = ButtonDefaults.contentPaddingFor(height),
      modifier = Modifier.fillMaxWidth().heightIn(min = height),
    ) {
      Icon(painterResource(Res.drawable.ic_play), null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
      Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
      Text(stringResource(Res.string.backup_start), style = ButtonDefaults.textStyleFor(height))
    }
  }
}

private fun LazyListScope.transfers(list: List<Transfer>, actions: TransferActions, nav: BackupNav) {
  item(key = "transfers-title", contentType = "title") {
    Text(stringResource(Res.string.backup_transfers_title), style = MaterialTheme.typography.titleMedium)
  }
  if (list.isEmpty()) {
    item(key = "transfers-empty", contentType = "hint") { Hint(stringResource(Res.string.backup_transfers_empty)) }
  }
  items(list, key = { it.id }, contentType = { "transfer" }) { transfer ->
    TransferCard(transfer, actions, onOpen = { nav.onOpenTransfer(transfer.id) }, modifier = Modifier.animateItem())
  }
}

@Composable
private fun OfflineBanner() {
  ListItem(
    leadingContent = { Icon(painterResource(Res.drawable.ic_error), null) },
    headlineContent = { Text(stringResource(Res.string.backup_offline_title)) },
    supportingContent = { Text(stringResource(Res.string.backup_offline_body)) },
    colors =
      ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        headlineColor = MaterialTheme.colorScheme.onErrorContainer,
        supportingColor = MaterialTheme.colorScheme.onErrorContainer,
        leadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
      ),
    modifier = Modifier.fillMaxWidth(),
  )
}
