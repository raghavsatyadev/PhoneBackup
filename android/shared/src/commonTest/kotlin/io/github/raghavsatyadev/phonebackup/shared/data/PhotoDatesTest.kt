package io.github.raghavsatyadev.phonebackup.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDateTime

class PhotoDatesTest {
  @Test
  fun exif() {
    assertEquals(LocalDateTime(2021, 7, 4, 10, 30, 5), PhotoDates.parseExif("2021:07:04 10:30:05"))
    assertNull(PhotoDates.parseExif("0000:00:00 00:00:00"))
    assertNull(PhotoDates.parseExif(""))
  }

  @Test
  fun fileNames() {
    assertEquals(LocalDateTime(2024, 1, 2, 13, 14, 15), PhotoDates.parseFileName("IMG_20240102_131415.jpg"))
    assertEquals(LocalDateTime(2024, 1, 2, 13, 14, 15), PhotoDates.parseFileName("PXL_20240102_131415123.jpg"))
    assertEquals(LocalDateTime(2023, 12, 31, 23, 59, 1), PhotoDates.parseFileName("Screenshot_2023-12-31-23-59-01.png"))
    assertEquals(LocalDateTime(2024, 3, 5, 12, 0, 0), PhotoDates.parseFileName("IMG-20240305-WA0007.jpg"))
    assertNull(PhotoDates.parseFileName("photo.jpg"))
    assertNull(PhotoDates.parseFileName("IMG_20241399_000000.jpg"))
  }
}
