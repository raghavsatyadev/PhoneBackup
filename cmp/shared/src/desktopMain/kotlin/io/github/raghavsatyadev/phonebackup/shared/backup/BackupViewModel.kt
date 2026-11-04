package io.github.raghavsatyadev.phonebackup.shared.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.raghavsatyadev.phonebackup.shared.backup.data.BackupApi
import io.github.raghavsatyadev.phonebackup.shared.backup.data.Transfer
import io.github.raghavsatyadev.phonebackup.shared.backup.data.TransferFeed
import io.github.raghavsatyadev.phonebackup.shared.resources.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Backup tab. One instance for the app (a Koin single), so the form survives switching
 * tabs. The backup itself runs in the server; this only asks it and shows what it reports.
 */
class BackupViewModel(
  private val api: BackupApi,
  private val feed: TransferFeed,
  private val pickFolder: (suspend (String) -> String?)?,
  private val pickStatusFile: (suspend () -> String?)?,
) : ViewModel() {
  private val _state = MutableStateFlow(BackupUiState())
  val state: StateFlow<BackupUiState> = _state.asStateFlow()

  private val _messages = Channel<BackupMessage>(Channel.BUFFERED)
  val messages: Flow<BackupMessage> = _messages.receiveAsFlow()

  val canBrowse: Boolean get() = pickFolder != null
  val canImport: Boolean get() = pickStatusFile != null

  init {
    viewModelScope.launch {
      feed.transfers.collect { map ->
        val list = map.values.sortedByDescending { it.createdAt }.toImmutableList()
        _state.update { s -> s.copy(transfers = list, detected = s.detected?.let { d -> map[d.id] ?: d }) }
      }
    }
    viewModelScope.launch { feed.connected.collect { c -> _state.update { it.copy(connected = c) } } }
  }

  /** Keeps the phone list fresh while the screen is on show; call from the screen. */
  suspend fun watchDevices() {
    while (true) {
      loadDevices()
      delay(5_000)
    }
  }

  fun onEvent(event: BackupEvent) {
    when (event) {
      BackupEvent.RefreshDevices -> viewModelScope.launch { loadDevices() }
      is BackupEvent.SelectDevice -> _state.update { it.copy(selectedSerial = event.serial) }
      BackupEvent.OpenWifi -> _state.update { it.copy(wifiOpen = true, wifiError = null) }
      BackupEvent.CloseWifi -> _state.update { it.copy(wifiOpen = false, wifiBusy = false, wifiError = null) }
      is BackupEvent.WifiAddress -> _state.update { it.copy(wifiAddress = event.text, wifiError = null) }
      BackupEvent.ConnectWifi -> connectWifi()
      is BackupEvent.DisconnectWifi -> disconnectWifi(event.serial)
      is BackupEvent.AddSources -> addSources(event.paths)
      is BackupEvent.RemoveSource -> _state.update { s -> s.copy(sources = (s.sources - event.path).toImmutableList()) }
      BackupEvent.ClearSources -> _state.update { it.copy(sources = emptyList<String>().toImmutableList()) }
      is BackupEvent.PathDraft -> _state.update { it.copy(pathDraft = event.text) }
      BackupEvent.AddDraft -> {
        addSources(_state.value.pathDraft.split(',', '\n'))
        _state.update { it.copy(pathDraft = "") }
      }
      is BackupEvent.Filter -> _state.update { it.copy(filter = event.text) }
      is BackupEvent.Destination ->
        _state.update { it.copy(destination = event.text, destinationError = null, destinationNote = null, detected = null) }
      BackupEvent.CheckDestination -> checkDestination()
      BackupEvent.BrowseDestination -> browseDestination()
      BackupEvent.Start -> start()
      is BackupEvent.Pause -> act { api.pause(event.id) }
      is BackupEvent.Cancel -> act { api.cancel(event.id) }
      is BackupEvent.Resume -> resume(event.transfer)
      BackupEvent.ConfirmMismatch -> {
        val m = _state.value.mismatch ?: return
        _state.update { it.copy(mismatch = null) }
        resume(m.transfer, m.newSerial)
      }
      BackupEvent.DismissMismatch -> _state.update { it.copy(mismatch = null) }
      is BackupEvent.RetryFailed -> retryFailed(event.transfer)
      BackupEvent.ImportStatusFile -> importStatusFile()
    }
  }

  private suspend fun loadDevices() {
    val devices = runCatching { api.devices() }.getOrNull()
    _state.update { s ->
      if (devices == null) return@update s.copy(devicesLoaded = true)
      val keep = s.selectedSerial?.takeIf { serial -> devices.any { it.serial == serial } }
      s.copy(devices = devices.toImmutableList(), devicesLoaded = true, selectedSerial = keep ?: devices.firstOrNull()?.serial)
    }
  }

  private fun connectWifi() {
    val address = _state.value.wifiAddress.trim()
    if (address.isEmpty()) return
    _state.update { it.copy(wifiBusy = true, wifiError = null) }
    viewModelScope.launch {
      try {
        api.connectWifi(address)
        loadDevices()
        // The server adds :5555 when no port is given.
        val serial = if (':' in address) address else "$address:5555"
        _state.update { s ->
          s.copy(
            wifiOpen = false,
            wifiBusy = false,
            wifiAddress = "",
            selectedSerial = if (s.devices.any { it.serial == serial }) serial else s.selectedSerial,
          )
        }
        say(Res.string.backup_wifi_connected, address)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(wifiBusy = false, wifiError = e.message.orEmpty()) }
      }
    }
  }

  private fun disconnectWifi(serial: String) =
    act {
      api.disconnectWifi(serial)
      loadDevices()
    }

  private fun addSources(paths: List<String>) {
    val clean = paths.map { it.trim().trimEnd('/') }.filter { it.startsWith("/") }
    if (clean.isEmpty()) return
    _state.update { s -> s.copy(sources = (s.sources + clean).distinct().toImmutableList()) }
  }

  private fun checkDestination(path: String = _state.value.destination) {
    val target = path.trim()
    if (target.isEmpty()) return
    _state.update { it.copy(checkingDestination = true, destinationError = null, destinationNote = null) }
    viewModelScope.launch {
      try {
        val info = api.setDestination(target)
        info.transfer?.let(feed::put)
        val detected = info.transfer?.takeIf { info.hasStatus }
        val note =
          when {
            detected != null -> null
            info.created -> Res.string.backup_folder_created
            else -> Res.string.backup_folder_ready
          }
        _state.update {
          it.copy(
            destination = info.path.ifBlank { target },
            checkingDestination = false,
            detected = detected,
            destinationNote = note,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _state.update { it.copy(checkingDestination = false, destinationError = e.message.orEmpty()) }
      }
    }
  }

  private fun browseDestination() {
    val pick = pickFolder ?: return
    viewModelScope.launch {
      val chosen = pick(_state.value.destination) ?: return@launch
      _state.update { it.copy(destination = chosen, destinationError = null, destinationNote = null, detected = null) }
      checkDestination(chosen)
    }
  }

  private fun start() {
    val s = _state.value
    val serial = s.selectedSerial ?: return
    if (!s.canStart) return
    val include = s.filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    _state.update { it.copy(starting = true) }
    viewModelScope.launch {
      try {
        feed.put(api.start(serial, s.sources, s.destination.trim(), include))
        say(Res.string.backup_started)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        say(Res.string.backup_error, e.message.orEmpty())
      } finally {
        _state.update { it.copy(starting = false) }
      }
    }
  }

  /** Resumes with the selected phone; asks first when that phone did not make this backup. */
  private fun resume(transfer: Transfer, confirmedSerial: String? = null) {
    val s = _state.value
    val selected = s.selectedSerial
    if (confirmedSerial == null && selected != null && transfer.activeSerial.isNotEmpty() && !transfer.belongsTo(selected)) {
      val newLabel = s.selectedDevice?.label ?: selected
      val oldLabel = transfer.deviceModel.ifBlank { transfer.activeSerial }
      _state.update { it.copy(mismatch = DeviceMismatch(transfer, oldLabel, selected, newLabel)) }
      return
    }
    val serial = confirmedSerial ?: selected ?: transfer.activeSerial
    act { feed.put(api.resume(transfer.id, serial)) }
  }

  private fun retryFailed(transfer: Transfer) {
    val serial = _state.value.selectedSerial?.takeIf { transfer.belongsTo(it) } ?: transfer.activeSerial
    act {
      val count = api.retryFailed(transfer.id, serial)
      say(Res.string.backup_retrying, count.toString())
    }
  }

  private fun importStatusFile() {
    val pick = pickStatusFile ?: return
    viewModelScope.launch {
      val file = pick() ?: return@launch
      try {
        val transfer = api.import(file)
        if (transfer != null) {
          feed.put(transfer)
          say(Res.string.backup_imported)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        say(Res.string.backup_error, e.message.orEmpty())
      }
    }
  }

  /** Runs a server action, re-reads the list afterwards, and reports a refusal. */
  private fun act(block: suspend () -> Unit) {
    viewModelScope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        say(Res.string.backup_error, e.message.orEmpty())
      }
      feed.refresh()
    }
  }

  private suspend fun say(text: org.jetbrains.compose.resources.StringResource, detail: String = "") =
    _messages.send(BackupMessage(text, detail))
}
