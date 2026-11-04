@file:OptIn(KoinExperimentalAPI::class)

package io.github.raghavsatyadev.phonebackup.shared.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.raghavsatyadev.phonebackup.shared.AppNav
import io.github.raghavsatyadev.phonebackup.shared.LocalAppNav
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.core.annotation.KoinExperimentalAPI

/**
 * The one NavDisplay both apps use, showing the screens declared in navigationModule. Screens opened
 * on top (Settings) get their own ViewModels, cleared when they close. Transitions: [NavTransitions].
 */
@Composable
fun AppNavDisplay(nav: AppNav) {
  val backStack = nav.backStack
  val screens = koinEntryProvider<NavKey>()
  val entries =
    remember(screens) {
      { key: NavKey ->
        val entry = screens(key)
        if (key == ShareKey || key == LibraryKey || key == BackupKey) {
          NavEntry(key, entry.contentKey, entry.metadata + NavTransitions.TabMetadata) { entry.Content() }
        } else {
          entry
        }
      }
    }
  CompositionLocalProvider(LocalAppNav provides nav) {
    NavDisplay(
      backStack = backStack,
      onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
      entryDecorators =
        listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
      transitionSpec = NavTransitions.push(),
      popTransitionSpec = NavTransitions.pop(),
      predictivePopTransitionSpec = NavTransitions.predictivePop(),
      entryProvider = entries,
    )
  }
}
