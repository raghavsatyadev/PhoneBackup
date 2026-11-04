package io.github.raghavsatyadev.phonebackup.desktop

import io.github.raghavsatyadev.phonebackup.shared.data.SavedServer
import io.github.raghavsatyadev.phonebackup.shared.data.ServerConfig
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import java.io.File

/**
 * Adds the PhoneBackup server running on this PC to the PC list, without pairing: the server
 * keeps its token and id in the user config folder (Go's os.UserConfigDir), which this app can
 * read. Runs on every start, so a new token is picked up too.
 */
fun addThisPc(settings: SettingsStore) {
  val dir = configDir() ?: return
  val token = read(File(dir, "phonebackup/upload_token"))?.takeIf { it.length >= 16 } ?: return
  val serverId = read(File(dir, "phonebackup/server_id")).orEmpty()
  settings.update { list ->
    val saved = list.servers.find { (serverId.isNotBlank() && it.serverId == serverId) || it.token == token }
    when {
      saved == null -> list.upsert(SavedServer(serverId = serverId, name = "This PC", address = LOCAL_ADDRESS, token = token))
      saved.token != token || saved.serverId.isBlank() ->
        list.upsert(saved.copy(token = token, serverId = serverId.ifBlank { saved.serverId }))
      else -> list
    }
  }
}

private val LOCAL_ADDRESS = "127.0.0.1:${ServerConfig.DEFAULT_PORT}"

private fun read(f: File): String? = runCatching { f.readText().trim() }.getOrNull()?.ifBlank { null }

internal fun configDir(): File? {
  val os = System.getProperty("os.name").orEmpty().lowercase()
  val home = System.getProperty("user.home")
  val path =
    when {
      os.startsWith("windows") -> System.getenv("APPDATA")
      os.contains("mac") -> "$home/Library/Application Support"
      else -> System.getenv("XDG_CONFIG_HOME")?.ifBlank { null } ?: "$home/.config"
    }
  return path?.let(::File)
}
