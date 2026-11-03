package io.github.raghavsatyadev.phonebackup.shared.ui.library

import io.github.raghavsatyadev.phonebackup.shared.data.GalleryPhoto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.collections.immutable.persistentListOf

class LibraryContractTest {
  private val a = GalleryPhoto("Trip/a.jpg", album = "Trip", takenAt = "2025-10-21T10:00:00Z", category = "Diwali", event = "Diwali", groupId = "g1", groupSize = 2, isBest = false)
  private val b = GalleryPhoto("Trip/b.jpg", album = "Trip", takenAt = "2025-10-21T09:00:00Z", category = "Diwali", event = "Diwali", groupId = "g1", groupSize = 2, isBest = true)
  private val c = GalleryPhoto("Unsorted/c.jpg", album = "Unsorted", takenAt = "2024-01-01T09:00:00Z")
  private val state = LibraryUiState(photos = persistentListOf(a, b, c))

  @Test
  fun bucketKeys() {
    assertNull(a.bucketKey(LibraryView.All, "None"))
    assertEquals("Trip", a.bucketKey(LibraryView.Albums, "None"))
    assertEquals("None", c.bucketKey(LibraryView.Categories, "None"))
    assertEquals("2025 Diwali", a.bucketKey(LibraryView.Events, "None"))
    assertEquals("2024", c.bucketKey(LibraryView.Events, "None"))
  }

  @Test
  fun hideSimilarKeepsBestShot() {
    assertEquals(listOf(b, c), state.gridPhotos("None"))
    assertEquals(listOf(a, b, c), state.copy(hideSimilar = false).gridPhotos("None"))
  }

  @Test
  fun bucketFiltersGrid() {
    val inTrip = state.copy(view = LibraryView.Albums, bucket = "Trip", hideSimilar = false)
    assertEquals(listOf(a, b), inTrip.gridPhotos("None"))
    val events = state.copy(view = LibraryView.Events).buckets("None").map { it.name }
    assertEquals(listOf("2025 Diwali", "2024"), events)
  }

  @Test
  fun similarIsBestFirst() {
    assertEquals(listOf(b, a), state.similarTo("Trip/a.jpg"))
    assertEquals(listOf(c), state.similarTo("Unsorted/c.jpg"))
    assertEquals(emptyList(), state.similarTo("missing"))
  }
}
