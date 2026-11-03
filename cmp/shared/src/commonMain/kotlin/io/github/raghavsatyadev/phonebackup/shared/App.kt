package io.github.raghavsatyadev.phonebackup.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import io.github.raghavsatyadev.phonebackup.shared.nav.AppNavDisplay
import io.github.raghavsatyadev.phonebackup.shared.nav.LibraryKey
import io.github.raghavsatyadev.phonebackup.shared.nav.NavConfig
import io.github.raghavsatyadev.phonebackup.shared.nav.SettingsKey
import io.github.raghavsatyadev.phonebackup.shared.nav.ShareKey
import io.github.raghavsatyadev.phonebackup.shared.nav.open
import io.github.raghavsatyadev.phonebackup.shared.nav.selectTab
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_cloud_upload
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_photo_library
import io.github.raghavsatyadev.phonebackup.shared.resources.no_category
import io.github.raghavsatyadev.phonebackup.shared.resources.tab_library
import io.github.raghavsatyadev.phonebackup.shared.resources.tab_share
import io.github.raghavsatyadev.phonebackup.shared.ui.AppImageLoader
import io.github.raghavsatyadev.phonebackup.shared.ui.library.LibraryRoute
import io.github.raghavsatyadev.phonebackup.shared.ui.library.LibraryViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.settings.SettingsRoute
import io.github.raghavsatyadev.phonebackup.shared.ui.settings.SettingsViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.share.ShareRoute
import io.github.raghavsatyadev.phonebackup.shared.ui.share.ShareViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.AppTheme
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The Android app: Share and Library tabs, plus Settings. */
@Composable
fun App(graph: AppGraph, onPickPhotos: () -> Unit) {
  AppTheme {
    AppImageLoader(graph)
    // Created here, not per entry, so switching tabs keeps the form and the loaded library.
    val share = viewModel { ShareViewModel(graph.queue, graph.settings) }
    val library = libraryViewModel(graph)
    val backStack = rememberStartStack(graph, ShareKey)
    // Photos shared from another app always land on the Share tab.
    LaunchedEffect(graph.queue) { graph.queue.deliveries.drop(1).collect { backStack.selectTab(ShareKey) } }
    val top = backStack.lastOrNull()
    Scaffold(
      bottomBar = {
        if (top == ShareKey || top == LibraryKey) {
          NavigationBar {
            Tab(backStack, ShareKey, Res.drawable.ic_cloud_upload, Res.string.tab_share)
            Tab(backStack, LibraryKey, Res.drawable.ic_photo_library, Res.string.tab_library)
          }
        }
      }
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        AppNavDisplay(backStack) {
          entry<ShareKey> {
            ShareRoute(share, onPickPhotos = onPickPhotos, onOpenSettings = { backStack.open(SettingsKey) })
          }
          entry<LibraryKey> { LibraryRoute(library, onOpenSettings = { backStack.open(SettingsKey) }) }
          settingsEntry(graph, backStack)
        }
      }
    }
  }
}

/** The desktop app: the photo viewer and Settings only (sharing happens on the phone). */
@Composable
fun ViewerApp(graph: AppGraph) {
  AppTheme {
    AppImageLoader(graph)
    val library = libraryViewModel(graph)
    val backStack = rememberStartStack(graph, LibraryKey)
    AppNavDisplay(backStack) {
      entry<LibraryKey> { LibraryRoute(library, onOpenSettings = { backStack.open(SettingsKey) }) }
      settingsEntry(graph, backStack)
    }
  }
}

/** Opens Settings over [root] on first run, so Back from it lands on the main screen. */
@Composable
private fun rememberStartStack(graph: AppGraph, root: NavKey): NavBackStack<NavKey> =
  if (graph.settings.config.value.isComplete) rememberNavBackStack(NavConfig, root)
  else rememberNavBackStack(NavConfig, root, SettingsKey)

private fun EntryProviderScope<NavKey>.settingsEntry(graph: AppGraph, backStack: NavBackStack<NavKey>) {
  entry<SettingsKey> {
    val vm = viewModel { SettingsViewModel(graph.settings, graph.uploads) }
    SettingsRoute(vm, onBack = { backStack.removeLastOrNull() })
  }
}

@Composable
private fun libraryViewModel(graph: AppGraph): LibraryViewModel {
  val uncategorized = stringResource(Res.string.no_category)
  return viewModel { LibraryViewModel(graph.settings, graph.gallery) { uncategorized } }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(
  backStack: NavBackStack<NavKey>,
  key: NavKey,
  icon: DrawableResource,
  label: StringResource,
) {
  NavigationBarItem(
    selected = backStack.lastOrNull() == key,
    onClick = { backStack.selectTab(key) },
    icon = { Icon(painterResource(icon), null) },
    label = { Text(stringResource(label)) },
  )
}
