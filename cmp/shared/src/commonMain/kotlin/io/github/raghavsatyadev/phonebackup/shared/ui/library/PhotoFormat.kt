package io.github.raghavsatyadev.phonebackup.shared.ui.library

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2025-10-21T19:05:00+05:30" becomes "21 Oct 2025, 19:05" in this device's time zone (raw text when unreadable). */
fun formatTakenAt(raw: String): String {
  val t = runCatching { Instant.parse(raw).toLocalDateTime(TimeZone.currentSystemDefault()) }.getOrNull() ?: return raw
  val hh = t.hour.toString().padStart(2, '0')
  val mm = t.minute.toString().padStart(2, '0')
  return "${t.day} ${months[t.month.ordinal]} ${t.year}, $hh:$mm"
}
