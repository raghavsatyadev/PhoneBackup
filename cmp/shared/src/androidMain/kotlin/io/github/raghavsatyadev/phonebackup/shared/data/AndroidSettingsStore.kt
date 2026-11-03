package io.github.raghavsatyadev.phonebackup.shared.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidSettingsStore(context: Context) : SettingsStore {
  private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
  private val state =
    MutableStateFlow(
      ServerConfig(
        address = prefs.getString(KEY_ADDRESS, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, "").orEmpty(),
      )
    )
  override val config: StateFlow<ServerConfig> = state

  override fun save(config: ServerConfig) {
    val clean = ServerConfig(config.address.trim(), config.token.trim())
    prefs.edit().putString(KEY_ADDRESS, clean.address).putString(KEY_TOKEN, clean.token).apply()
    state.value = clean
  }

  override fun loadDraft() =
    ShareDraft(
      album = prefs.getString(KEY_ALBUM, "").orEmpty(),
      tags = prefs.getString(KEY_TAGS, "").orEmpty().split(',').filter { it.isNotBlank() },
    )

  override fun saveDraft(draft: ShareDraft) {
    prefs.edit().putString(KEY_ALBUM, draft.album).putString(KEY_TAGS, draft.tags.joinToString(",")).apply()
  }

  private companion object {
    const val KEY_ADDRESS = "address"
    const val KEY_TOKEN = "token"
    const val KEY_ALBUM = "draft_album"
    const val KEY_TAGS = "draft_tags"
  }
}
