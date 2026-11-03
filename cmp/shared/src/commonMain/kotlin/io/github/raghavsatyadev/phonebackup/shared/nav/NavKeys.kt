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

/** Saved PCs, PCs found on the network, and which one receives photos. */
@Serializable data object ServersKey : NavKey

/** Adds a PC by address ([localId] null) or edits a saved one. */
@Serializable data class ServerEditKey(val localId: String? = null) : NavKey

/** Asks the PC at [address] to allow this phone; the PC answers with the token. */
@Serializable data class PairKey(val address: String, val name: String) : NavKey

/** Saves the back stack on every platform: Nav3 needs each [NavKey] registered. */
val NavConfig = SavedStateConfiguration {
  serializersModule = SerializersModule {
    polymorphic(NavKey::class) {
      subclass(ShareKey::class, ShareKey.serializer())
      subclass(LibraryKey::class, LibraryKey.serializer())
      subclass(ServersKey::class, ServersKey.serializer())
      subclass(ServerEditKey::class, ServerEditKey.serializer())
      subclass(PairKey::class, PairKey.serializer())
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
