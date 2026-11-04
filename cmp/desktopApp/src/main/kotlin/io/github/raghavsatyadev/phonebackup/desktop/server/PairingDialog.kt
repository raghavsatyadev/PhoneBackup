package io.github.raghavsatyadev.phonebackup.desktop.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** "Allow this phone to connect?" for the oldest waiting phone, with the code it shows. */
@Composable
fun PairingDialog(requests: PairingRequests) {
  val pending by requests.pending.collectAsState()
  val request = pending.firstOrNull() ?: return
  AlertDialog(
    onDismissRequest = {},
    title = { Text("Allow this phone to connect?") },
    text = {
      Column {
        Text("${request.name.ifBlank { "A phone" }} (${request.ip}) wants to send photos to this PC.")
        Spacer(Modifier.height(16.dp))
        Text("Check the phone shows this code:")
        Text(request.code, style = MaterialTheme.typography.displaySmall)
      }
    },
    confirmButton = { TextButton(onClick = { requests.decide(request.id, allow = true) }) { Text("Allow") } },
    dismissButton = { TextButton(onClick = { requests.decide(request.id, allow = false) }) { Text("Deny") } },
  )
}
