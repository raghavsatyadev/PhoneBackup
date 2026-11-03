package io.github.raghavsatyadev.phonebackup.discovery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Android 17's local network permission. Without it, every search opens the system's "Choose a
 * device" sheet, so the app asks once and searches only when it was granted.
 */
class LocalNetworkAccess(private val context: Context) {
  /** `true` on Android 17 and later, where the permission exists. */
  val needed: Boolean = Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK

  private val answered = MutableStateFlow(!needed || granted())

  fun granted(): Boolean =
    !needed || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

  /** The user answered the request (either way); the search may start. */
  fun onAnswered() {
    answered.value = true
  }

  /** Waits until the user answered, or returns at once when nothing is asked. */
  suspend fun awaitAnswer() {
    answered.first { it }
  }

  companion object {
    const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
    private const val LOCAL_NETWORK_SDK = 37
  }
}
