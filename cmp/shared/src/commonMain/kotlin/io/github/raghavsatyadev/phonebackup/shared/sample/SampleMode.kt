@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.sample_banner_exit
import io.github.raghavsatyadev.phonebackup.shared.resources.sample_banner_title
import io.github.raghavsatyadev.phonebackup.shared.resources.sample_entry_button
import io.github.raghavsatyadev.phonebackup.shared.resources.sample_entry_text
import io.github.raghavsatyadev.phonebackup.shared.resources.sample_entry_title
import org.jetbrains.compose.resources.stringResource

/**
 * Lets someone without the PC server (a store reviewer, a curious user) try the phone app on a
 * made-up PC with sample photos ([SampleServer]). Switching restarts the app, so sample and real
 * data never mix. Only Android offers it ([available]).
 */
interface SampleMode {
  /** True while the app talks to the sample PC instead of real ones. */
  val active: Boolean

  /** False where there is no sample mode (the desktop app). */
  val available: Boolean

  /** Restarts the app on the sample PC. */
  fun start()

  /** Restarts the app on the real PCs, as they were before. */
  fun exit()
}

/** Desktop: no sample mode. */
object NoSampleMode : SampleMode {
  override val active = false
  override val available = false

  override fun start() {}

  override fun exit() {}
}

/** The way into sample mode, at the bottom of the PC list. */
@Composable
fun SampleModeEntry(mode: SampleMode, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      stringResource(Res.string.sample_entry_title),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.semantics { heading() },
    )
    Text(
      stringResource(Res.string.sample_entry_text),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FilledTonalButton(onClick = mode::start, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(Res.string.sample_entry_button))
    }
  }
}

/** Always on top in sample mode, so nobody takes the sample photos for real ones. */
@Composable
fun SampleModeBanner(mode: SampleMode, modifier: Modifier = Modifier) {
  Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = modifier.fillMaxWidth()) {
    Row(
      Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        stringResource(Res.string.sample_banner_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      TextButton(
        onClick = mode::exit,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
      ) {
        Text(stringResource(Res.string.sample_banner_exit))
      }
    }
  }
}
