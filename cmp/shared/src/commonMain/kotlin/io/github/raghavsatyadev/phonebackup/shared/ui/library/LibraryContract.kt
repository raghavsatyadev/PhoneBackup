package io.github.raghavsatyadev.phonebackup.shared.ui.library

import io.github.raghavsatyadev.phonebackup.shared.data.GalleryPhoto
import io.github.raghavsatyadev.phonebackup.shared.data.ServerConfig
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

enum class LibraryView { All, Albums, Categories, Events }

/** An album, category or event, shown as a cover tile. */
data class Bucket(val name: String, val count: Int, val cover: GalleryPhoto)

/** The full-screen viewer: which photos it pages through and where it starts. */
data class ViewerState(val ids: ImmutableList<String>, val index: Int)

data class LibraryUiState(
  val config: ServerConfig = ServerConfig(),
  val loading: Boolean = false,
  val error: String? = null,
  val photos: ImmutableList<GalleryPhoto> = persistentListOf(),
  val view: LibraryView = LibraryView.All,
  val bucket: String? = null,
  val hideSimilar: Boolean = true,
  val viewer: ViewerState? = null,
)

sealed interface LibraryEvent {
  data object Refresh : LibraryEvent

  data class SelectView(val view: LibraryView) : LibraryEvent

  data class OpenBucket(val name: String) : LibraryEvent

  data object ToggleHideSimilar : LibraryEvent

  /** Open [id] in the viewer, paging through the photos currently in the grid. */
  data class Open(val id: String) : LibraryEvent

  /** Open [id] in the viewer, paging through its group of similar photos. */
  data class OpenSimilar(val id: String) : LibraryEvent

  data object Back : LibraryEvent
}

/** Folder name the photo falls under in [view]; null when it doesn't belong to one. */
fun GalleryPhoto.bucketKey(view: LibraryView, uncategorized: String): String? =
  when (view) {
    LibraryView.All -> null
    LibraryView.Albums -> album
    LibraryView.Categories -> category.ifBlank { uncategorized }
    // Same naming as the server's organize command: "<year>[ <event>]".
    LibraryView.Events -> listOf(takenAt.take(4), event.trim()).filter { it.isNotBlank() }.joinToString(" ")
  }

/** Photos after the "hide similar" filter: one per group (its best shot). */
fun LibraryUiState.shown(): List<GalleryPhoto> = if (hideSimilar) photos.filter { it.isBest } else photos

/** Cover tiles for the Albums / Categories / Events tabs, busiest first within the newest order. */
fun LibraryUiState.buckets(uncategorized: String): List<Bucket> =
  shown()
    .groupBy { it.bucketKey(view, uncategorized) }
    .filterKeys { !it.isNullOrBlank() }
    .map { (name, list) -> Bucket(name!!, list.size, list.first()) }
    .let { if (view == LibraryView.Events) it.sortedByDescending { b -> b.cover.takenAt } else it }

/** What the photo grid shows right now. */
fun LibraryUiState.gridPhotos(uncategorized: String): ImmutableList<GalleryPhoto> {
  val list = shown()
  if (view == LibraryView.All || bucket == null) return list.toImmutableList()
  return list.filter { it.bucketKey(view, uncategorized) == bucket }.toImmutableList()
}

/** Every photo in [id]'s group, best first. */
fun LibraryUiState.similarTo(id: String): List<GalleryPhoto> {
  val photo = photos.firstOrNull { it.id == id } ?: return emptyList()
  if (photo.groupId.isEmpty()) return listOf(photo)
  return photos.filter { it.groupId == photo.groupId }.sortedByDescending { it.isBest }
}
