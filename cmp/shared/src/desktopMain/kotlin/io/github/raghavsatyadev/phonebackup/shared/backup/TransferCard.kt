@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.raghavsatyadev.phonebackup.shared.backup.data.Transfer
import io.github.raghavsatyadev.phonebackup.shared.resources.*
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.animationsEnabled
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** What the transfer cards and the detail screen can ask for. */
class TransferActions(
  val onPause: (String) -> Unit,
  val onResume: (Transfer) -> Unit,
  val onCancel: (String) -> Unit,
  val onRetry: (Transfer) -> Unit,
)

/** A backup with its progress and controls; [onOpen] shows the detail screen. */
@Composable
fun TransferCard(transfer: Transfer, actions: TransferActions, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
  val colors =
    if (transfer.isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    else CardDefaults.cardColors()
  val content: @Composable () -> Unit = {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      TransferSummary(transfer)
      TransferControls(transfer, actions, onOpen)
    }
  }
  if (onOpen != null) {
    Card(onClick = onOpen, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = colors) { content() }
  } else {
    Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = colors) { content() }
  }
}

/** Folder, status, progress bar and counts. */
@Composable
fun TransferSummary(transfer: Transfer) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(
        transfer.localDir,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.StartEllipsis,
      )
      Text(
        listOf(transfer.deviceModel.ifBlank { transfer.activeSerial }, transfer.sources.joinToString { fileName(it) })
          .filter { it.isNotBlank() }
          .joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Spacer(Modifier.width(8.dp))
    StatusBadge(transfer.status)
  }
  ProgressBar(transfer.progress)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Stat(stringResource(Res.string.backup_files_count, transfer.handledFiles, transfer.totalFiles))
    Stat(stringResource(Res.string.backup_bytes_count, formatBytes(transfer.doneBytes), formatBytes(transfer.totalBytes)))
    if (transfer.status == Transfer.STATUS_RUNNING) {
      Stat(formatSpeed(transfer.speedBps))
      if (transfer.etaSeconds > 0) Stat(stringResource(Res.string.backup_eta, formatDuration(transfer.etaSeconds)))
    }
    if (transfer.skippedFiles > 0) Stat(stringResource(Res.string.backup_skipped_count, transfer.skippedFiles))
    if (transfer.failedFiles > 0) {
      Text(
        stringResource(Res.string.backup_failed_count, transfer.failedFiles),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
      )
    }
  }
  if (transfer.status == Transfer.STATUS_RUNNING && transfer.currentFile.isNotBlank()) {
    Text(
      stringResource(Res.string.backup_current_file, fileName(transfer.currentFile)),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  if (transfer.error.isNotBlank()) {
    Text(transfer.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
  }
}

@Composable
private fun ProgressBar(progress: Float) {
  val shown by animateFloatAsState(progress, MaterialTheme.motionScheme.slowEffectsSpec())
  // Wavy whatever the value (a bar that turns flat near the ends reads as a flicker).
  if (animationsEnabled()) {
    LinearWavyProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth(), amplitude = { 1f })
  } else {
    LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
  }
}

@Composable
private fun Stat(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun StatusBadge(status: String) {
  val (container, content) =
    when (status) {
      Transfer.STATUS_COMPLETED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
      Transfer.STATUS_FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
      Transfer.STATUS_RUNNING, Transfer.STATUS_SCANNING ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
      else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
  androidx.compose.material3.Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
    Text(
      statusLabel(status),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
  }
}

@Composable
fun TransferControls(transfer: Transfer, actions: TransferActions, onOpen: (() -> Unit)?) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (transfer.isActive) {
      ActionButton(Res.drawable.ic_pause, Res.string.backup_pause, tonal = true) { actions.onPause(transfer.id) }
    }
    if (transfer.canResume) {
      ActionButton(Res.drawable.ic_play, Res.string.backup_resume, tonal = true) { actions.onResume(transfer) }
    }
    if (transfer.failedFiles > 0 && !transfer.isActive) {
      ActionButton(Res.drawable.ic_refresh, Res.string.backup_retry_failed) { actions.onRetry(transfer) }
    }
    if (transfer.isActive || transfer.status == Transfer.STATUS_PAUSED) {
      ActionButton(Res.drawable.ic_close, Res.string.backup_cancel) { actions.onCancel(transfer.id) }
    }
    if (onOpen != null) {
      TextButton(onClick = onOpen, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.backup_details)) }
    }
  }
}

@Composable
private fun ActionButton(
  icon: DrawableResource,
  label: org.jetbrains.compose.resources.StringResource,
  tonal: Boolean = false,
  onClick: () -> Unit,
) {
  val inner: @Composable () -> Unit = {
    Icon(painterResource(icon), null, Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(stringResource(label))
  }
  if (tonal) {
    FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { inner() }
  } else {
    OutlinedButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { inner() }
  }
}
