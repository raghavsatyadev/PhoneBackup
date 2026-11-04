package io.github.raghavsatyadev.phonebackup.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.Icon
import androidx.compose.ui.input.nestedscroll.nestedScroll
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.rememberHideOnScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import io.github.raghavsatyadev.phonebackup.shared.nav.AppNavDisplay
import io.github.raghavsatyadev.phonebackup.shared.nav.LibraryKey
import io.github.raghavsatyadev.phonebackup.shared.nav.NavConfig
import io.github.raghavsatyadev.phonebackup.shared.nav.ServersKey
import io.github.raghavsatyadev.phonebackup.shared.nav.ShareKey
import io.github.raghavsatyadev.phonebackup.shared.nav.selectTab
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_cloud_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_photo_library
import io.github.raghavsatyadev.phonebackup.shared.resources.no_category
import io.github.raghavsatyadev.phonebackup.shared.resources.tab_library
import io.github.raghavsatyadev.phonebackup.shared.resources.tab_share
import io.github.raghavsatyadev.phonebackup.shared.ui.AppImageLoader
import io.github.raghavsatyadev.phonebackup.shared.ui.library.LibraryViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.share.ShareViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.AppTheme
import io.github.raghavsatyadev.phonebackup.shared.data.NetworkServers
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.shared.data.ShareQueue
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.saveable.rememberSaveable
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The Android app: Share and Library tabs, plus the PC list. */
@Composable
fun App(onPickPhotos: () -> Unit) {
  AppTheme {
    AppImageLoader()
    // Created here, not per entry, so switching tabs keeps the form and the loaded library.
    val share = koinViewModel<ShareViewModel>()
    val queue = koinInject<ShareQueue>()
    val library = libraryViewModel()
    val backStack = rememberStartStack(ShareKey)
    WatchNetwork()
    // Photos shared from another app always land on the Share tab.
    LaunchedEffect(queue) { queue.deliveries.drop(1).collect { backStack.selectTab(ShareKey) } }
    val top = backStack.lastOrNull()
    val onTab = top == ShareKey || top == LibraryKey
    val bars = rememberHideOnScroll()
    // A bottom bar on phones, a rail on tablets and unfolded foldables.
    val type = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())
    val isBar =
      type == NavigationSuiteType.ShortNavigationBarCompact ||
        type == NavigationSuiteType.ShortNavigationBarMedium ||
        type == NavigationSuiteType.NavigationBar
    val suite = rememberNavigationSuiteScaffoldState()
    // Only the bottom bar scrolls away; the rail stays while a tab is open.
    val showSuite = onTab && (bars.visible || !isBar)
    LaunchedEffect(showSuite) { if (showSuite) suite.show() else suite.hide() }
    NavigationSuiteScaffold(
      navigationItems = {
        Tab(backStack, ShareKey, Res.drawable.ic_cloud_upload, Res.string.tab_share)
        Tab(backStack, LibraryKey, Res.drawable.ic_photo_library, Res.string.tab_library)
      },
      navigationSuiteType = type,
      state = suite,
    ) {
      Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).nestedScroll(bars.connection)) {
          AppNavDisplay(AppNav(backStack, library, share, onPickPhotos))
        }
      }
    }
  }
}

/** The desktop app: the photo viewer and the PC list only (sharing happens on the phone). */
@Composable
fun ViewerApp() {
  AppTheme {
    AppImageLoader()
    val library = libraryViewModel()
    val backStack = rememberStartStack(LibraryKey)
    WatchNetwork()
    AppNavDisplay(AppNav(backStack, library, share = null, onPickPhotos = {}))
  }
}

/** Opens the PC list over [root] on first run, so Back from it lands on the main screen. */
@Composable
private fun rememberStartStack(root: NavKey): NavBackStack<NavKey> {
  // Decided once: pairing later must not swap in a fresh stack (Back would then leave the app).
  val settings = koinInject<SettingsStore>()
  val firstRun = rememberSaveable { !settings.config.value.isComplete }
  return if (firstRun) rememberNavBackStack(NavConfig, root, ServersKey) else rememberNavBackStack(NavConfig, root)
}

/** Looks for PCs on the network while the app is on screen. */
@Composable
private fun WatchNetwork() {
  val network = koinInject<NetworkServers>()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(network, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { network.watch() } }
}

@Composable
private fun libraryViewModel(): LibraryViewModel {
  val uncategorized = stringResource(Res.string.no_category)
  val label: () -> String = { uncategorized }
  return koinViewModel { parametersOf(label) }
}

@Composable
private fun Tab(
  backStack: NavBackStack<NavKey>,
  key: NavKey,
  icon: DrawableResource,
  label: StringResource,
) {
  NavigationSuiteItem(
    selected = backStack.lastOrNull() == key,
    onClick = { backStack.selectTab(key) },
    icon = { Icon(painterResource(icon), null) },
    label = { Text(stringResource(label)) },
  )
}
