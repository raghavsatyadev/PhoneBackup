@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import io.github.raghavsatyadev.phonebackup.shared.model.UploadStatus
import io.github.raghavsatyadev.phonebackup.shared.model.isFinished
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_cancel_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.action_clear_finished
import io.github.raghavsatyadev.phonebackup.shared.resources.action_retry_failed
import io.github.raghavsatyadev.phonebackup.shared.resources.action_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_check_circle
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_close
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_cloud_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_refresh
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.animationsEnabled
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val ShareUiState.allDone get() = photos.isNotEmpty() && photos.all { it.isFinished }

/** Copy and upload progress, pinned below the list only while something is running. */
@Composable
fun UploadProgressBar(state: ShareUiState) {
  if (state.copying == null && !state.isUploading) return
  Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
    Column(
      Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      state.copying?.let { CopyingProgress(it) }
      if (state.isUploading) {
        val done = state.photos.count { it.isFinished || it.status == UploadStatus.Failed }
        val target = (done + state.currentProgress) / state.photos.size.coerceAtLeast(1)
        val progress by animateFloatAsState(target, MaterialTheme.motionScheme.slowEffectsSpec())
        if (animationsEnabled()) {
          LinearWavyProgressIndicator(progress = { progress }, amplitude = { 1f }, modifier = Modifier.fillMaxWidth())
        } else {
          LinearProgressIndicator(progress = { progress }, strokeCap = StrokeCap.Round, modifier = Modifier.fillMaxWidth())
        }
      }
    }
  }
}

/** The screen's main action as a floating button; shrinks to its icon while the list scrolls. */
@Composable
fun UploadFab(state: ShareUiState, onEvent: (ShareEvent) -> Unit, expanded: Boolean) {
  val retry = state.photos.any { it.status == UploadStatus.Failed }
  val (icon, label, event) =
    when {
      state.allDone -> Triple(Res.drawable.ic_check_circle, Res.string.action_clear_finished, ShareEvent.ClearFinished)
      state.isUploading -> Triple(Res.drawable.ic_close, Res.string.action_cancel_upload, ShareEvent.CancelUpload)
      !state.canUpload -> return
      retry -> Triple(Res.drawable.ic_refresh, Res.string.action_retry_failed, ShareEvent.Upload)
      else -> Triple(Res.drawable.ic_cloud_upload, Res.string.action_upload, ShareEvent.Upload)
    }
  val c = MaterialTheme.colorScheme
  val quiet = state.allDone || state.isUploading
  ExtendedFloatingActionButton(
    onClick = { onEvent(event) },
    expanded = expanded,
    icon = { Icon(painterResource(icon), null) },
    text = { Text(stringResource(label)) },
    containerColor = if (quiet) c.secondaryContainer else c.primaryContainer,
    contentColor = if (quiet) c.onSecondaryContainer else c.onPrimaryContainer,
  )
}

/** Whether the PC picker belongs in the top bar (hidden once every photo is sent). */
internal val ShareUiState.showPcPicker get() = photos.isNotEmpty() && !allDone
