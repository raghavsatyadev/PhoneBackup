@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.raghavsatyadev.phonebackup.shared.backup.data.FileItem
import io.github.raghavsatyadev.phonebackup.shared.backup.data.LogEntry
import io.github.raghavsatyadev.phonebackup.shared.backup.data.Transfer
import io.github.raghavsatyadev.phonebackup.shared.resources.*
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun TransferRoute(viewModel: TransferViewModel, actions: TransferActions, onBack: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  TransferScreen(state, actions, onBack)
}

@Composable
fun TransferScreen(state: TransferUiState, actions: TransferActions, onBack: () -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(Res.string.backup_transfer_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.action_back))
          }
        },
      )
    },
  ) { padding ->
    val transfer = state.transfer
    if (transfer == null) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.backup_transfer_gone), color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      return@Scaffold
    }
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      item(key = "summary") { Section { TransferSummary(transfer); TransferControls(transfer, actions, onOpen = null) } }
      item(key = "speed") { Section { SectionTitle(Res.string.backup_speed_title); SpeedChart(state.speeds) } }
      if (state.failed.isNotEmpty() || state.failedError != null) {
        item(key = "failed-title") { SectionTitleRow(Res.string.backup_failed_title, transfer) }
        state.failedError?.let { err ->
          item(key = "failed-error") { Text(err, color = MaterialTheme.colorScheme.error) }
        }
        items(state.failed, key = { "f-" + it.remotePath }, contentType = { "failed" }) { FailedRow(it) }
      }
      item(key = "log-title") { Box(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth()) { SectionTitle(Res.string.backup_log_title) } }
      if (transfer.recentLogs.isEmpty()) {
        item(key = "log-empty") {
          Text(stringResource(Res.string.backup_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      // Newest first; the time and path together are unique enough for a key.
      items(transfer.recentLogs.asReversed(), key = { "l-" + it.time + it.remotePath + it.status }, contentType = { "log" }) {
        LogRow(it)
      }
    }
  }
}

private val MAX_WIDTH = 880.dp

@Composable
private fun Section(content: @Composable () -> Unit) {
  ElevatedCard(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth(), shape = MaterialTheme.shapes.large) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
  }
}

@Composable
private fun SectionTitle(text: org.jetbrains.compose.resources.StringResource) {
  Text(stringResource(text), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SectionTitleRow(text: org.jetbrains.compose.resources.StringResource, transfer: Transfer) {
  Row(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      stringResource(text) + " (${transfer.failedFiles})",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.weight(1f),
    )
  }
}

/** The last two minutes of speed, as a line scaled to its own peak. */
@Composable
private fun SpeedChart(samples: ImmutableList<Float>) {
  val line = MaterialTheme.colorScheme.primary
  val grid = MaterialTheme.colorScheme.outlineVariant
  val peak = samples.maxOrNull() ?: 0f
  Text(
    stringResource(Res.string.backup_speed_peak, formatSpeed(peak.toDouble())),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Canvas(Modifier.fillMaxWidth().height(140.dp)) {
    val stroke = 1.dp.toPx()
    for (i in 0..3) {
      val y = size.height * i / 3f
      drawLine(grid, Offset(0f, y), Offset(size.width, y), stroke)
    }
    if (samples.size < 2 || peak <= 0f) return@Canvas
    val step = size.width / (MAX_POINTS - 1)
    val start = size.width - step * (samples.size - 1)
    val path = Path()
    samples.forEachIndexed { i, v ->
      val x = start + step * i
      val y = size.height - (v / peak) * size.height * 0.92f
      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}

private const val MAX_POINTS = 120

@Composable
private fun FailedRow(item: FileItem) {
  Column(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth().padding(vertical = 4.dp)) {
    Text(item.remotePath, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.StartEllipsis)
    Text(
      listOf(formatBytes(item.size), item.error).filter { it.isNotBlank() }.joinToString(" · "),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
    )
  }
}

@Composable
private fun LogRow(entry: LogEntry) {
  val color =
    when (entry.status) {
      "failed" -> MaterialTheme.colorScheme.error
      "skipped" -> MaterialTheme.colorScheme.onSurfaceVariant
      else -> MaterialTheme.colorScheme.onSurface
    }
  Row(Modifier.widthIn(max = MAX_WIDTH).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(entry.time.substringAfter('T').take(8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(statusLabelForLog(entry.status), style = MaterialTheme.typography.labelMedium, color = color)
    Text(
      fileName(entry.remotePath) + if (entry.error.isNotBlank()) " — ${entry.error}" else "",
      style = MaterialTheme.typography.bodySmall,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (entry.size > 0) Text(formatBytes(entry.size), style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun statusLabelForLog(status: String): String =
  when (status) {
    "failed" -> stringResource(Res.string.backup_log_failed)
    "skipped" -> stringResource(Res.string.backup_log_skipped)
    else -> stringResource(Res.string.backup_log_done)
  }
