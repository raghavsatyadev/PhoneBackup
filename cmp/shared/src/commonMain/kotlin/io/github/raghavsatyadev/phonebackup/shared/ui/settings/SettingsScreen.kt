@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_arrow_back
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_check
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_check_circle
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_error
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_key
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_wifi
import io.github.raghavsatyadev.phonebackup.shared.resources.saved
import io.github.raghavsatyadev.phonebackup.shared.resources.settings_intro
import io.github.raghavsatyadev.phonebackup.shared.resources.settings_title
import io.github.raghavsatyadev.phonebackup.shared.resources.test_failed
import io.github.raghavsatyadev.phonebackup.shared.resources.test_ok
import io.github.raghavsatyadev.phonebackup.shared.resources.test_running
import io.github.raghavsatyadev.phonebackup.shared.resources.token_label
import io.github.raghavsatyadev.phonebackup.shared.ui.BackHandler
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.DelayedLoadingIndicator
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  BackHandler(onBack = onBack)
  SettingsScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack)
}

@Composable
fun SettingsScreen(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit, onBack: () -> Unit) {
  Scaffold(
    modifier = Modifier.imePadding(),
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.settings_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.action_back))
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        stringResource(Res.string.settings_intro),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = state.address,
        onValueChange = { onEvent(SettingsEvent.AddressChanged(it)) },
        label = { Text(stringResource(Res.string.address_label)) },
        placeholder = { Text(stringResource(Res.string.address_placeholder)) },
        leadingIcon = { Icon(painterResource(Res.drawable.ic_wifi), null) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = state.token,
        onValueChange = { onEvent(SettingsEvent.TokenChanged(it)) },
        label = { Text(stringResource(Res.string.token_label)) },
        leadingIcon = { Icon(painterResource(Res.drawable.ic_key), null) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )
      val height = ButtonDefaults.MediumContainerHeight
      Button(
        onClick = { onEvent(SettingsEvent.Test) },
        enabled = state.address.isNotBlank() && state.token.isNotBlank() && state.test != TestResult.Running,
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height),
        modifier = Modifier.fillMaxWidth().heightIn(min = height),
      ) {
        Icon(painterResource(Res.drawable.ic_wifi), null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        Text(stringResource(Res.string.action_test), style = ButtonDefaults.textStyleFor(height))
      }
      OutlinedButton(
        onClick = { onEvent(SettingsEvent.Save) },
        enabled = !state.saved,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (state.saved) {
          Icon(painterResource(Res.drawable.ic_check), null, Modifier.size(ButtonDefaults.IconSize))
          Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(stringResource(if (state.saved) Res.string.saved else Res.string.action_save))
      }
      TestResultView(state.test)
    }
  }
}

@Composable
private fun TestResultView(test: TestResult?) {
  val motion = MaterialTheme.motionScheme
  val fadeInSpec = motion.defaultEffectsSpec<Float>()
  val scaleSpec = motion.defaultSpatialSpec<Float>()
  val fadeOutSpec = motion.fastEffectsSpec<Float>()
  AnimatedContent(
    targetState = test,
    transitionSpec = {
      (fadeIn(fadeInSpec) + scaleIn(scaleSpec, initialScale = 0.96f)) togetherWith fadeOut(fadeOutSpec)
    },
    contentKey = { it?.let { r -> r::class } },
    label = "test-result",
  ) { t ->
    val c = MaterialTheme.colorScheme
    when (t) {
      null -> Box(Modifier.fillMaxWidth())
      TestResult.Running ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          DelayedLoadingIndicator(Modifier.size(40.dp))
          Text(stringResource(Res.string.test_running), style = MaterialTheme.typography.bodyLarge)
        }
      is TestResult.Ok ->
        ResultCard(Res.drawable.ic_check_circle, stringResource(Res.string.test_ok, t.host, t.folder), c.primaryContainer, c.onPrimaryContainer)
      is TestResult.Failed ->
        ResultCard(Res.drawable.ic_error, stringResource(Res.string.test_failed, t.reason), c.errorContainer, c.onErrorContainer)
    }
  }
}

@Composable
private fun ResultCard(icon: DrawableResource, text: String, container: Color, content: Color) {
  Card(
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(painterResource(icon), null)
      Text(text, style = MaterialTheme.typography.bodyMedium)
    }
  }
}
