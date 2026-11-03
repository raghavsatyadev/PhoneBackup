package io.github.raghavsatyadev.phonebackup.shared.ui.share

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_cancel
import io.github.raghavsatyadev.phonebackup.shared.resources.action_next
import io.github.raghavsatyadev.phonebackup.shared.resources.action_ok
import io.github.raghavsatyadev.phonebackup.shared.resources.date_dialog_title
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** Date picker, then time picker. Material3 pickers work in UTC millis, so convert at the edges. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun DateTimeDialog(initial: LocalDateTime, onDismiss: () -> Unit, onConfirm: (LocalDateTime) -> Unit) {
  var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
  val date = pickedDate
  if (date == null) {
    val dateState =
      rememberDatePickerState(
        initialSelectedDateMillis = initial.date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
      )
    DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(
          onClick = {
            val millis = dateState.selectedDateMillis ?: return@TextButton
            pickedDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
          },
          enabled = dateState.selectedDateMillis != null,
        ) {
          Text(stringResource(Res.string.action_next))
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    ) {
      DatePicker(state = dateState)
    }
  } else {
    val timeState = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
    AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(Res.string.date_dialog_title)) },
      text = { TimePicker(state = timeState) },
      confirmButton = {
        TextButton(
          onClick = { onConfirm(LocalDateTime(date, LocalTime(timeState.hour, timeState.minute, initial.second))) }
        ) {
          Text(stringResource(Res.string.action_ok))
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
  }
}

/** "2024-05-01 14:30" — fixed format so it reads the same everywhere. */
fun LocalDateTime.display(): String {
  fun Int.two() = toString().padStart(2, '0')
  return "$year-${month.ordinal.plus(1).two()}-${day.two()} ${hour.two()}:${minute.two()}"
}
