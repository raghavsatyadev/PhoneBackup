package io.github.raghavsatyadev.phonebackup.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.raghavsatyadev.phonebackup.shared.ui.BackHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_back
import io.github.raghavsatyadev.phonebackup.shared.resources.action_save
import io.github.raghavsatyadev.phonebackup.shared.resources.action_test
import io.github.raghavsatyadev.phonebackup.shared.resources.address_label
import io.github.raghavsatyadev.phonebackup.shared.resources.address_placeholder
import io.github.raghavsatyadev.phonebackup.shared.resources.saved
import io.github.raghavsatyadev.phonebackup.shared.resources.settings_intro
import io.github.raghavsatyadev.phonebackup.shared.resources.settings_title
import io.github.raghavsatyadev.phonebackup.shared.resources.test_failed
import io.github.raghavsatyadev.phonebackup.shared.resources.test_ok
import io.github.raghavsatyadev.phonebackup.shared.resources.test_running
import io.github.raghavsatyadev.phonebackup.shared.resources.token_label
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  BackHandler(onBack = onBack)
  SettingsScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit, onBack: () -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.settings_title)) },
        navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(Res.string.action_back)) } },
      )
    }
  ) { padding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(stringResource(Res.string.settings_intro), style = MaterialTheme.typography.bodyMedium)
      OutlinedTextField(
        value = state.address,
        onValueChange = { onEvent(SettingsEvent.AddressChanged(it)) },
        label = { Text(stringResource(Res.string.address_label)) },
        placeholder = { Text(stringResource(Res.string.address_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = state.token,
        onValueChange = { onEvent(SettingsEvent.TokenChanged(it)) },
        label = { Text(stringResource(Res.string.token_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onEvent(SettingsEvent.Save) }, enabled = !state.saved) {
          Text(stringResource(if (state.saved) Res.string.saved else Res.string.action_save))
        }
        Button(
          onClick = { onEvent(SettingsEvent.Test) },
          enabled = state.address.isNotBlank() && state.token.isNotBlank() && state.test != TestResult.Running,
        ) {
          Text(stringResource(Res.string.action_test))
        }
      }
      when (val t = state.test) {
        null -> Unit
        TestResult.Running -> Text(stringResource(Res.string.test_running))
        is TestResult.Ok ->
          Text(
            stringResource(Res.string.test_ok, t.host, t.folder),
            color = MaterialTheme.colorScheme.primary,
          )
        is TestResult.Failed ->
          Text(stringResource(Res.string.test_failed, t.reason), color = MaterialTheme.colorScheme.error)
      }
    }
  }
}
