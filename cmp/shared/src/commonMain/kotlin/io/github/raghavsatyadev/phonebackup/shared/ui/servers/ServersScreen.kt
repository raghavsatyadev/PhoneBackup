@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_add_by_address
import io.github.raghavsatyadev.phonebackup.shared.resources.action_back
import io.github.raghavsatyadev.phonebackup.shared.resources.action_edit
import io.github.raghavsatyadev.phonebackup.shared.resources.action_pair
import io.github.raghavsatyadev.phonebackup.shared.resources.action_remove
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_add
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_arrow_back
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_computer
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_delete
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_edit
import io.github.raghavsatyadev.phonebackup.shared.resources.servers_active
import io.github.raghavsatyadev.phonebackup.shared.resources.servers_found
import io.github.raghavsatyadev.phonebackup.shared.resources.servers_needs_pairing
import io.github.raghavsatyadev.phonebackup.shared.resources.servers_none_found
import io.github.raghavsatyadev.phonebackup.shared.resources.servers_none_saved
import io.github.raghavsatyadev.phonebackup.shared.resources.servers_paired
import io.github.raghavsatyadev.phonebackup.shared.resources.servers_saved
import io.github.raghavsatyadev.phonebackup.shared.resources.servers_searching
import io.github.raghavsatyadev.phonebackup.shared.resources.servers_title
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.DelayedLoadingIndicator
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ServersRoute(
  viewModel: ServersViewModel,
  onBack: () -> Unit,
  onAdd: () -> Unit,
  onEdit: (localId: String) -> Unit,
  onPair: (address: String, name: String) -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  ServersScreen(state, viewModel::onEvent, onBack, onAdd, onEdit, onPair)
}

@Composable
fun ServersScreen(
  state: ServersUiState,
  onEvent: (ServersEvent) -> Unit,
  onBack: () -> Unit,
  onAdd: () -> Unit,
  onEdit: (localId: String) -> Unit,
  onPair: (address: String, name: String) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.servers_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.action_back))
          }
        },
      )
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onAdd,
        icon = { Icon(painterResource(Res.drawable.ic_add), null) },
        text = { Text(stringResource(Res.string.action_add_by_address)) },
      )
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (state.canSearch) {
        item { Header(stringResource(Res.string.servers_found)) }
        if (state.found.isEmpty()) item { Searching() }
        items(state.found, key = { "found-" + it.server.serverId }) { row ->
          FoundCard(row, onPair = { onPair(row.server.address, row.server.name) })
        }
      }
      item { Header(stringResource(Res.string.servers_saved)) }
      if (state.saved.isEmpty()) {
        item { Hint(stringResource(Res.string.servers_none_saved)) }
      }
      items(state.saved, key = { "saved-" + it.localId }) { row ->
        SavedCard(
          row = row,
          onSelect = { onEvent(ServersEvent.Activate(row.localId)) },
          onEdit = { onEdit(row.localId) },
          onRemove = { onEvent(ServersEvent.Remove(row.localId)) },
          onPair = { onPair(row.address, row.label) },
        )
      }
    }
  }
}

@Composable
private fun Header(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
  )
}

@Composable
private fun Hint(text: String) {
  Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Searching() {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    DelayedLoadingIndicator(Modifier.size(32.dp))
    Hint(stringResource(Res.string.servers_searching) + "\n" + stringResource(Res.string.servers_none_found))
  }
}

@Composable
private fun FoundCard(row: FoundRow, onPair: () -> Unit) {
  Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
    ListItem(
      headlineContent = { Text(row.server.name.ifBlank { row.server.address }) },
      supportingContent = {
        val status = if (row.paired) Res.string.servers_paired else Res.string.servers_needs_pairing
        Text(row.server.address + " · " + stringResource(status))
      },
      leadingContent = { Icon(painterResource(Res.drawable.ic_computer), null) },
      trailingContent = {
        if (!row.paired) {
          FilledTonalButton(onClick = onPair, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(Res.string.action_pair))
          }
        }
      },
      colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
  }
}

@Composable
private fun SavedCard(row: SavedRow, onSelect: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit, onPair: () -> Unit) {
  val c = MaterialTheme.colorScheme
  Card(
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = if (row.active) c.secondaryContainer else c.surfaceContainer),
    modifier = Modifier.fillMaxWidth(),
  ) {
    ListItem(
      modifier = Modifier.selectable(selected = row.active, enabled = row.paired, role = Role.RadioButton, onClick = onSelect),
      headlineContent = { Text(row.label) },
      supportingContent = {
        val status =
          when {
            !row.paired -> stringResource(Res.string.servers_needs_pairing)
            row.active -> stringResource(Res.string.servers_active)
            else -> row.address
          }
        Text(status)
      },
      leadingContent = { RadioButton(selected = row.active, onClick = null, enabled = row.paired) },
      trailingContent = {
        Row {
          if (!row.paired) {
            FilledTonalButton(onClick = onPair, shapes = ButtonDefaults.shapes()) {
              Text(stringResource(Res.string.action_pair))
            }
          }
          IconButton(onClick = onEdit, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_edit), stringResource(Res.string.action_edit))
          }
          IconButton(onClick = onRemove, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_delete), stringResource(Res.string.action_remove))
          }
        }
      },
      colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
  }
}
