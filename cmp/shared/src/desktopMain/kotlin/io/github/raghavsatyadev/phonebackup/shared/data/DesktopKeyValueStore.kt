package io.github.raghavsatyadev.phonebackup.shared.data

import java.util.prefs.Preferences

/** Settings in the user's Java preferences (the registry on Windows). */
class DesktopKeyValueStore : KeyValueStore {
  private val prefs = Preferences.userRoot().node("io/github/raghavsatyadev/phonebackup")

  override fun get(key: String): String? = prefs.get(key, null)

  override fun put(values: Map<String, String>) {
    values.forEach { (k, v) -> prefs.put(k, v) }
    prefs.flush()
  }
}
