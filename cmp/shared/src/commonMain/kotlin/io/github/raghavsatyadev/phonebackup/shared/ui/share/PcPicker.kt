package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_manage_pcs
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_computer
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_settings
import io.github.raghavsatyadev.phonebackup.shared.resources.no_pc
import io.github.raghavsatyadev.phonebackup.shared.resources.send_to
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** "Send to: <PC>" with a menu of paired PCs; locked while an upload runs. */
@Composable
fun PcPicker(state: ShareUiState, onEvent: (ShareEvent) -> Unit, onManagePcs: () -> Unit) {
  var open by remember { mutableStateOf(false) }
  val active = state.pcs.find { it.localId == state.activePc }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      stringResource(Res.string.send_to),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box {
      TextButton(onClick = { open = true }, enabled = !state.isUploading, shapes = ButtonDefaults.shapes()) {
        Icon(painterResource(Res.drawable.ic_computer), null, Modifier.size(ButtonDefaults.IconSize))
        Text(
          active?.label ?: stringResource(Res.string.no_pc),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
        )
        Text(" ▾")
      }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        state.pcs.forEach { pc ->
          DropdownMenuItem(
            text = { Text(pc.label) },
            leadingIcon = { RadioButton(selected = pc.localId == state.activePc, onClick = null) },
            onClick = {
              open = false
              onEvent(ShareEvent.SelectPc(pc.localId))
            },
          )
        }
        if (state.pcs.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
          text = { Text(stringResource(Res.string.action_manage_pcs)) },
          leadingIcon = { Icon(painterResource(Res.drawable.ic_settings), null) },
          onClick = {
            open = false
            onManagePcs()
          },
        )
      }
    }
  }
}
