@file:OptIn(KoinExperimentalAPI::class)

package io.github.raghavsatyadev.phonebackup.shared

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.raghavsatyadev.phonebackup.shared.nav.LibraryKey
import io.github.raghavsatyadev.phonebackup.shared.nav.PairKey
import io.github.raghavsatyadev.phonebackup.shared.nav.ServerEditKey
import io.github.raghavsatyadev.phonebackup.shared.nav.ServersKey
import io.github.raghavsatyadev.phonebackup.shared.nav.ShareKey
import io.github.raghavsatyadev.phonebackup.shared.nav.open
import io.github.raghavsatyadev.phonebackup.shared.ui.library.LibraryRoute
import io.github.raghavsatyadev.phonebackup.shared.ui.library.LibraryViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.servers.PairRoute
import io.github.raghavsatyadev.phonebackup.shared.ui.servers.PairViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.servers.ServersRoute
import io.github.raghavsatyadev.phonebackup.shared.ui.servers.ServersViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.settings.SettingsRoute
import io.github.raghavsatyadev.phonebackup.shared.ui.settings.SettingsViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.share.ShareRoute
import io.github.raghavsatyadev.phonebackup.shared.ui.share.ShareViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/**
 * What the screens need from [App] or [ViewerApp], provided around the NavDisplay. The tab
 * ViewModels live up there so switching tabs keeps them; [share] is null in the desktop viewer.
 */
@Immutable
class AppNav(
  val backStack: NavBackStack<NavKey>,
  val library: LibraryViewModel,
  val share: ShareViewModel?,
  val onPickPhotos: () -> Unit,
)

val LocalAppNav = staticCompositionLocalOf<AppNav> { error("No AppNav") }

private fun NavBackStack<NavKey>.back() {
  removeLastOrNull()
}

/** Every screen, one Koin `navigation` entry per key; [AppNavDisplay] shows them. */
val navigationModule = module {
  navigation<ShareKey> {
    val nav = LocalAppNav.current
    nav.share?.let { share ->
      ShareRoute(share, onPickPhotos = nav.onPickPhotos, onOpenSettings = { nav.backStack.open(ServersKey) })
    }
  }
  navigation<LibraryKey> {
    val nav = LocalAppNav.current
    LibraryRoute(nav.library, onOpenSettings = { nav.backStack.open(ServersKey) })
  }
  navigation<ServersKey> {
    val backStack = LocalAppNav.current.backStack
    ServersRoute(
      koinViewModel<ServersViewModel>(),
      onBack = { backStack.back() },
      onAdd = { backStack.open(ServerEditKey()) },
      onEdit = { backStack.open(ServerEditKey(it)) },
      onPair = { address, name -> backStack.open(PairKey(address, name)) },
    )
  }
  navigation<ServerEditKey> { key ->
    val backStack = LocalAppNav.current.backStack
    SettingsRoute(
      koinViewModel<SettingsViewModel> { parametersOf(key.localId) },
      isNew = key.localId == null,
      onBack = { backStack.back() },
      onPair = { address, name -> backStack.open(PairKey(address, name)) },
    )
  }
  navigation<PairKey> { key ->
    val backStack = LocalAppNav.current.backStack
    PairRoute(
      koinViewModel<PairViewModel> { parametersOf(key.address, key.name) },
      name = key.name,
      onBack = { backStack.back() },
      // Back to the PC list (or the screen that opened pairing), skipping the address form.
      onDone = {
        backStack.back()
        if (backStack.lastOrNull() is ServerEditKey) backStack.back()
      },
    )
  }
}
