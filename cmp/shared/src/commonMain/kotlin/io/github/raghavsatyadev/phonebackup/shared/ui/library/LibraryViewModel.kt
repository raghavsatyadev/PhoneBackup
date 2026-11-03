package io.github.raghavsatyadev.phonebackup.shared.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.data.GalleryClient
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
  private val settings: SettingsStore,
  private val gallery: GalleryClient,
  /** Bucket name for photos without a category (a string resource on screen). */
  private val uncategorized: () -> String = { "Uncategorized" },
) : ViewModel() {
  private val _state = MutableStateFlow(LibraryUiState())
  val state: StateFlow<LibraryUiState> = _state.asStateFlow()
  private var loadJob: Job? = null

  init {
    viewModelScope.launch {
      settings.config.collect { c ->
        _state.update { it.copy(config = c) }
        if (c.isComplete) load()
      }
    }
  }

  fun onEvent(event: LibraryEvent) {
    when (event) {
      LibraryEvent.Refresh -> load()
      is LibraryEvent.SelectView -> _state.update { it.copy(view = event.view, bucket = null) }
      is LibraryEvent.OpenBucket -> _state.update { it.copy(bucket = event.name) }
      LibraryEvent.ToggleHideSimilar -> _state.update { it.copy(hideSimilar = !it.hideSimilar) }
      is LibraryEvent.Open ->
        _state.update { s ->
          val ids = s.gridPhotos(uncategorized()).map { it.id }
          s.copy(viewer = ViewerState(ids.toImmutableList(), ids.indexOf(event.id).coerceAtLeast(0)))
        }
      is LibraryEvent.OpenSimilar ->
        _state.update { s ->
          val ids = s.similarTo(event.id).map { it.id }
          s.copy(viewer = ViewerState(ids.toImmutableList(), ids.indexOf(event.id).coerceAtLeast(0)))
        }
      LibraryEvent.Back ->
        _state.update { s ->
          when {
            s.viewer != null -> s.copy(viewer = null)
            s.bucket != null -> s.copy(bucket = null)
            else -> s
          }
        }
    }
  }

  private fun load() {
    val config = _state.value.config
    if (!config.isComplete) return
    loadJob?.cancel()
    loadJob =
      viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
          val photos = gallery.photos(config)
          _state.update { it.copy(loading = false, photos = photos.toImmutableList()) }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
      }
  }
}
