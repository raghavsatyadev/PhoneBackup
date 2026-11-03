package io.github.raghavsatyadev.phonebackup.shared.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Pure date parsing used by the platform importers. */
object PhotoDates {
  // EXIF stores "yyyy:MM:dd HH:mm:ss" (sometimes with '-' or 'T').
  private val exifPattern = Regex("""^(\d{4})[:\-](\d{2})[:\-](\d{2})[ T](\d{2}):(\d{2}):(\d{2})""")

  // IMG_20240101_123456, PXL_20240101_123456789, Screenshot_2024-01-01-12-34-56,
  // IMG-20240101-WA0001 (WhatsApp strips EXIF, so the name is all we have).
  private val namePattern =
    Regex("""(?<!\d)((?:19|20)\d{2})[-_.]?(\d{2})[-_.]?(\d{2})(?:[-_ T.]?(\d{2})[-_.:]?(\d{2})[-_.:]?(\d{2}))?""")

  fun parseExif(value: String?): LocalDateTime? {
    val m = exifPattern.find(value?.trim() ?: return null) ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    return build(y, mo, d, h, mi, s)
  }

  fun parseFileName(name: String): LocalDateTime? {
    for (m in namePattern.findAll(name)) {
      val g = m.groupValues
      val dt =
        if (g[4].isNotEmpty()) build(g[1], g[2], g[3], g[4], g[5], g[6])
        else build(g[1], g[2], g[3], "12", "00", "00")
      if (dt != null) return dt
    }
    return null
  }

  private fun build(y: String, mo: String, d: String, h: String, mi: String, s: String): LocalDateTime? =
    runCatching {
        val date = LocalDate(y.toInt(), mo.toInt(), d.toInt())
        if (date.year < 1990 || date.year > 2100) return null
        LocalDateTime(date, LocalTime(h.toInt(), mi.toInt(), s.toInt()))
      }
      .getOrNull()
}
