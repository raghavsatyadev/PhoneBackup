package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.album_label
import io.github.raghavsatyadev.phonebackup.shared.resources.album_new
import io.github.raghavsatyadev.phonebackup.shared.resources.album_placeholder
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_folder
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Album name: pick one of the PC's existing albums from the menu, or type a new name (the PC
 * creates the folder on the first upload).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumField(value: String, albums: ImmutableList<String>, enabled: Boolean, onChange: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val typed = value.trim()
  val matches =
    remember(typed, albums) {
      if (typed.isEmpty()) albums else albums.filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
    }
  val isNew = typed.isNotEmpty() && albums.isNotEmpty() && albums.none { it.equals(typed, ignoreCase = true) }
  val showMenu = expanded && enabled && matches.isNotEmpty()

  ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }) {
    OutlinedTextField(
      value = value,
      onValueChange = {
        onChange(it)
        expanded = true
      },
      label = { Text(stringResource(Res.string.album_label)) },
      placeholder = { Text(stringResource(Res.string.album_placeholder)) },
      leadingIcon = { Icon(painterResource(Res.drawable.ic_folder), null) },
      trailingIcon = { if (albums.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded = showMenu) },
      supportingText = if (isNew) ({ Text(stringResource(Res.string.album_new, typed)) }) else null,
      singleLine = true,
      enabled = enabled,
      shape = MaterialTheme.shapes.medium,
      modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled),
    )
    ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
      matches.forEach { album ->
        DropdownMenuItem(
          text = { Text(album) },
          leadingIcon = { Icon(painterResource(Res.drawable.ic_folder), null) },
          onClick = {
            onChange(album)
            expanded = false
          },
          contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
        )
      }
    }
  }
}
