package io.github.raghavsatyadev.phonebackup.shared.ui.servers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.about_title
import io.github.raghavsatyadev.phonebackup.shared.resources.privacy_policy
import io.github.raghavsatyadev.phonebackup.shared.resources.privacy_policy_hint
import org.jetbrains.compose.resources.stringResource

/** The public privacy policy (also linked from the store listing). */
const val PRIVACY_POLICY_URL = "https://raghavsatyadev.github.io/projects/phonebackup/privacy/"

/** "About" at the bottom of the PC list: the privacy policy link. */
@Composable
fun AboutSection(modifier: Modifier = Modifier) {
  val uri = LocalUriHandler.current
  Text(
    stringResource(Res.string.about_title),
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
  )
  ListItem(
    headlineContent = { Text(stringResource(Res.string.privacy_policy)) },
    supportingContent = { Text(stringResource(Res.string.privacy_policy_hint)) },
    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { uri.openUri(PRIVACY_POLICY_URL) },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
  )
}
