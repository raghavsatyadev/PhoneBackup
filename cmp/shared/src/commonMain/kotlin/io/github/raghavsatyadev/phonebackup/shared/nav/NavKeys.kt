package io.github.raghavsatyadev.phonebackup.shared.nav

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/** The Share tab (Android only): photos waiting to go to the PC. */
@Serializable data object ShareKey : NavKey

/** The Library tab: photos already on the PC. */
@Serializable data object LibraryKey : NavKey

/** Server address and token. */
@Serializable data object SettingsKey : NavKey

/** Saves the back stack on every platform: Nav3 needs each [NavKey] registered. */
val NavConfig = SavedStateConfiguration {
  serializersModule = SerializersModule {
    polymorphic(NavKey::class) {
      subclass(ShareKey::class, ShareKey.serializer())
      subclass(LibraryKey::class, LibraryKey.serializer())
      subclass(SettingsKey::class, SettingsKey.serializer())
    }
  }
}

/** Opens [key] on top unless it is already showing. */
fun NavBackStack<NavKey>.open(key: NavKey) {
  if (lastOrNull() != key) add(key)
}

/**
 * Shows a tab. Share is always at the bottom, so Back from Library returns to Share and the
 * Share entry keeps its saved state while Library is open.
 */
fun NavBackStack<NavKey>.selectTab(tab: NavKey) {
  while (size > 1) removeAt(lastIndex)
  if (firstOrNull() != ShareKey) {
    clear()
    add(ShareKey)
  }
  if (tab != ShareKey) add(tab)
}
