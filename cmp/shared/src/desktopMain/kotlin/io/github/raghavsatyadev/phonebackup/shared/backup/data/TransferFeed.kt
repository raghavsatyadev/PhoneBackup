package io.github.raghavsatyadev.phonebackup.shared.backup.data

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Every backup the server knows about, kept live over its WebSocket. Reconnects with backoff
 * and re-reads the full list on each connect, so nothing is missed while the server restarts.
 */
class TransferFeed(private val api: BackupApi, scope: CoroutineScope) {
  private val _transfers = MutableStateFlow<Map<String, Transfer>>(emptyMap())
  private val _connected = MutableStateFlow(false)
  private val _speeds = MutableStateFlow<Map<String, ImmutableList<Float>>>(emptyMap())
  private val lastSample = mutableMapOf<String, Long>()

  val transfers: StateFlow<Map<String, Transfer>> = _transfers.asStateFlow()
  val connected: StateFlow<Boolean> = _connected.asStateFlow()

  /** Recent speed samples per transfer (bytes/s, about one a second), for the chart. */
  val speeds: StateFlow<Map<String, ImmutableList<Float>>> = _speeds.asStateFlow()

  init {
    scope.launch {
      var wait = 1_000L
      while (true) {
        try {
          api.watch(
            onOpen = {
              _connected.value = true
              wait = 1_000L
              refresh()
            },
            onUpdate = ::put,
          )
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Server not running yet, or it went away; try again below.
        }
        _connected.value = false
        delay(wait)
        wait = (wait * 2).coerceAtMost(10_000L)
      }
    }
  }

  /** Re-reads the list from the server (also after an action, in case an update was lost). */
  suspend fun refresh() {
    val list = runCatching { api.transfers() }.getOrNull() ?: return
    _transfers.value = list.associateBy { it.id }
  }

  /** Puts a transfer the server returned from an action. */
  fun put(transfer: Transfer) {
    _transfers.update { map ->
      // The server keeps one backup per folder; a new id for the same folder replaces the old one.
      val others = map.filterValues { it.id == transfer.id || !samePath(it.localDir, transfer.localDir) }
      others + (transfer.id to transfer)
    }
    sampleSpeed(transfer)
  }

  private fun sampleSpeed(transfer: Transfer) {
    val now = System.currentTimeMillis()
    val running = transfer.status == Transfer.STATUS_RUNNING
    if (running && now - (lastSample[transfer.id] ?: 0L) < 1_000L) return
    lastSample[transfer.id] = now
    _speeds.update { map ->
      val old = map[transfer.id] ?: persistentListOf()
      val value = if (running) transfer.speedBps.toFloat() else 0f
      if (!running && old.lastOrNull() == 0f) return@update map
      map + (transfer.id to (old + value).takeLast(MAX_SAMPLES).toImmutableList())
    }
  }

  private companion object {
    const val MAX_SAMPLES = 120

    fun samePath(a: String, b: String): Boolean =
      a.trimEnd('\\', '/').equals(b.trimEnd('\\', '/'), ignoreCase = true)
  }
}
