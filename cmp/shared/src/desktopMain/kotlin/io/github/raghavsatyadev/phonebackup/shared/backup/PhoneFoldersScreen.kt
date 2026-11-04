@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.raghavsatyadev.phonebackup.shared.resources.*
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.DelayedLoadingIndicator
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun PhoneFoldersRoute(viewModel: PhoneFoldersViewModel, onBack: () -> Unit, onAdd: (List<String>) -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  PhoneFoldersScreen(state, viewModel::onEvent, onBack, onAdd)
}

@Composable
fun PhoneFoldersScreen(
  state: PhoneFoldersUiState,
  onEvent: (PhoneFoldersEvent) -> Unit,
  onBack: () -> Unit,
  onAdd: (List<String>) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(stringResource(Res.string.backup_folders_title))
            Text(
              state.path,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.StartEllipsis,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.action_back))
          }
        },
        actions = {
          IconButton(onClick = { onEvent(PhoneFoldersEvent.Reload) }, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_refresh), stringResource(Res.string.backup_reload))
          }
        },
      )
    },
    bottomBar = {
      Button(
        onClick = { onAdd(state.checked.toList()) },
        enabled = state.checked.isNotEmpty(),
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = ButtonDefaults.MediumContainerHeight),
      ) {
        Text(pluralStringResource(Res.plurals.backup_add_folders, state.checked.size, state.checked.size))
      }
    },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when {
        state.loading -> DelayedLoadingIndicator(Modifier.align(Alignment.Center))
        state.error != null ->
          Text(
            stringResource(Res.string.backup_folders_error, state.error),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
          )
        else -> FolderList(state, onEvent)
      }
    }
  }
}

@Composable
private fun FolderList(state: PhoneFoldersUiState, onEvent: (PhoneFoldersEvent) -> Unit) {
  val folders = state.entries.filter { it.isDir }
  val files = state.entries.size - folders.size
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    if (state.canGoUp) {
      item(key = "..", contentType = "up") {
        ListItem(
          headlineContent = { Text(stringResource(Res.string.backup_folder_up)) },
          leadingContent = { Icon(painterResource(Res.drawable.ic_arrow_back), null) },
          colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
          modifier = Modifier.clickable { onEvent(PhoneFoldersEvent.Up) },
        )
      }
    }
    items(folders, key = { it.path }, contentType = { "folder" }) { entry ->
      ListItem(
        headlineContent = { Text(entry.name) },
        leadingContent = {
          Checkbox(checked = entry.path in state.checked, onCheckedChange = { onEvent(PhoneFoldersEvent.Toggle(entry.path)) })
        },
        trailingContent = { Icon(painterResource(Res.drawable.ic_chevron_right), stringResource(Res.string.backup_folder_open)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable { onEvent(PhoneFoldersEvent.Open(entry.path)) },
      )
    }
    if (folders.isEmpty()) {
      item(key = "empty", contentType = "hint") {
        Text(
          stringResource(Res.string.backup_folder_empty),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(16.dp),
        )
      }
    }
    if (files > 0) {
      item(key = "files", contentType = "hint") {
        Text(
          pluralStringResource(Res.plurals.backup_folder_files, files, files),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(16.dp),
        )
      }
    }
  }
}

