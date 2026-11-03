package io.github.raghavsatyadev.phonebackup.shared.data

import android.content.Context

/** Settings in SharedPreferences (the same file older versions used, so they migrate). */
class AndroidKeyValueStore(context: Context) : KeyValueStore {
  private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

  override fun get(key: String): String? = prefs.getString(key, null)

  override fun put(values: Map<String, String>) {
    prefs.edit().apply { values.forEach { (k, v) -> putString(k, v) } }.apply()
  }
}
