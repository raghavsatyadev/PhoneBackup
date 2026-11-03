@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.servers

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_back
import io.github.raghavsatyadev.phonebackup.shared.resources.action_done
import io.github.raghavsatyadev.phonebackup.shared.resources.action_try_again
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_arrow_back
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_check_circle
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_error
import io.github.raghavsatyadev.phonebackup.shared.resources.pair_allowed
import io.github.raghavsatyadev.phonebackup.shared.resources.pair_asking
import io.github.raghavsatyadev.phonebackup.shared.resources.pair_check_code
import io.github.raghavsatyadev.phonebackup.shared.resources.pair_dashboard_hint
import io.github.raghavsatyadev.phonebackup.shared.resources.pair_denied
import io.github.raghavsatyadev.phonebackup.shared.resources.pair_expired
import io.github.raghavsatyadev.phonebackup.shared.resources.pair_failed
import io.github.raghavsatyadev.phonebackup.shared.resources.pair_title
import io.github.raghavsatyadev.phonebackup.shared.resources.pair_waiting
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.DelayedLoadingIndicator
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun PairRoute(viewModel: PairViewModel, name: String, onBack: () -> Unit, onDone: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  PairScreen(state, name, onBack, onRetry = viewModel::start, onDone = onDone)
}

@Composable
fun PairScreen(state: PairState, name: String, onBack: () -> Unit, onRetry: () -> Unit, onDone: () -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.pair_title, name)) },
        navigationIcon = {
          IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.action_back))
          }
        },
      )
    }
  ) { padding ->
    AnimatedContent(
      targetState = state,
      contentKey = { it::class },
      modifier = Modifier.fillMaxSize().padding(padding),
      label = "pair",
    ) { s ->
      Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        when (s) {
          PairState.Asking -> {
            DelayedLoadingIndicator(Modifier.size(56.dp))
            Body(stringResource(Res.string.pair_asking))
          }
          is PairState.Waiting -> Waiting(s.code)
          is PairState.Allowed -> {
            Outcome(ok = true, text = stringResource(Res.string.pair_allowed, s.name))
            Action(stringResource(Res.string.action_done), onDone)
          }
          PairState.Denied -> Failed(stringResource(Res.string.pair_denied), onRetry)
          PairState.Expired -> Failed(stringResource(Res.string.pair_expired), onRetry)
          is PairState.Failed -> Failed(stringResource(Res.string.pair_failed, s.reason), onRetry)
        }
      }
    }
  }
}

@Composable
private fun Waiting(code: String) {
  Body(stringResource(Res.string.pair_check_code))
  val shown = if (code.length == 6) code.take(3) + " " + code.drop(3) else code
  Card(
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      shown,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      fontSize = 44.sp,
      letterSpacing = 6.sp,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).semantics { contentDescription = code.toList().joinToString(" ") },
    )
  }
  DelayedLoadingIndicator(Modifier.size(40.dp))
  Body(stringResource(Res.string.pair_waiting))
  Text(
    stringResource(Res.string.pair_dashboard_hint),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
  )
}

@Composable
private fun Failed(text: String, onRetry: () -> Unit) {
  Outcome(ok = false, text = text)
  Action(stringResource(Res.string.action_try_again), onRetry)
}

@Composable
private fun Outcome(ok: Boolean, text: String) {
  val c = MaterialTheme.colorScheme
  Icon(
    painterResource(if (ok) Res.drawable.ic_check_circle else Res.drawable.ic_error),
    null,
    tint = if (ok) c.primary else c.error,
    modifier = Modifier.size(56.dp),
  )
  Body(text)
}

@Composable
private fun Body(text: String) {
  Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
  val height = ButtonDefaults.MediumContainerHeight
  Button(
    onClick = onClick,
    shapes = ButtonDefaults.shapesFor(height),
    contentPadding = ButtonDefaults.contentPaddingFor(height),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(text, style = ButtonDefaults.textStyleFor(height))
  }
}
