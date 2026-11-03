package io.github.raghavsatyadev.phonebackup.shared.data

import io.github.raghavsatyadev.phonebackup.shared.model.IncomingPhoto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Hand-off point between the platform share receiver (which copies files while it still
 * holds the URI permission) and the share screen. Photos wait here until the screen drains
 * them, so nothing is lost while the screen is being created.
 */
class PhotoInbox {
  private val _pending = MutableStateFlow<List<IncomingPhoto>>(emptyList())
  val pending: StateFlow<List<IncomingPhoto>> = _pending

  fun deliver(photos: List<IncomingPhoto>) {
    if (photos.isNotEmpty()) _pending.update { it + photos }
  }

  fun drain(): List<IncomingPhoto> = _pending.getAndUpdate { emptyList() }
}
