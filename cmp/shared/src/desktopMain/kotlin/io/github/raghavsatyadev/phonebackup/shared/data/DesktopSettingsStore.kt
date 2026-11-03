package io.github.raghavsatyadev.phonebackup.shared.data

import java.util.prefs.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Keeps the server address and token in the user's Java preferences (the registry on Windows). */
class DesktopSettingsStore : SettingsStore {
  private val prefs = Preferences.userRoot().node("io/github/raghavsatyadev/phonebackup")
  private val state =
    MutableStateFlow(ServerConfig(prefs.get(KEY_ADDRESS, ""), prefs.get(KEY_TOKEN, "")))
  override val config: StateFlow<ServerConfig> = state

  override fun save(config: ServerConfig) {
    val clean = ServerConfig(config.address.trim(), config.token.trim())
    prefs.put(KEY_ADDRESS, clean.address)
    prefs.put(KEY_TOKEN, clean.token)
    prefs.flush()
    state.value = clean
  }

  private companion object {
    const val KEY_ADDRESS = "address"
    const val KEY_TOKEN = "token"
  }
}
