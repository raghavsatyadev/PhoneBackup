package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.backup.data.BackupApi
import io.github.raghavsatyadev.phonebackup.shared.backup.data.FileItem
import io.github.raghavsatyadev.phonebackup.shared.backup.data.Transfer
import io.github.raghavsatyadev.phonebackup.shared.backup.data.TransferFeed
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TransferUiState(
  /** Null once the server no longer knows the backup (another one took its folder). */
  val transfer: Transfer? = null,
  val speeds: ImmutableList<Float> = persistentListOf(),
  val failed: ImmutableList<FileItem> = persistentListOf(),
  val failedError: String? = null,
)

/** One backup in detail. Its controls go through [BackupViewModel], like the cards on the tab. */
class TransferViewModel(private val id: String, private val api: BackupApi, feed: TransferFeed) : ViewModel() {
  private val _state = MutableStateFlow(TransferUiState(transfer = feed.transfers.value[id]))
  val state: StateFlow<TransferUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      combine(feed.transfers.map { it[id] }, feed.speeds.map { it[id] ?: persistentListOf() }) { t, s -> t to s }
        .collect { (t, s) -> _state.update { it.copy(transfer = t, speeds = s) } }
    }
    // The failed list is a separate request; fetch it again only when the count changes.
    viewModelScope.launch {
      feed.transfers.map { it[id]?.failedFiles }.distinctUntilChanged().collect { count ->
        if (count == null) return@collect
        if (count == 0) _state.update { it.copy(failed = persistentListOf(), failedError = null) } else loadFailed()
      }
    }
  }

  private suspend fun loadFailed() {
    try {
      val items = api.failed(id)
      _state.update { it.copy(failed = items.toImmutableList(), failedError = null) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _state.update { it.copy(failedError = e.message.orEmpty()) }
    }
  }
}
