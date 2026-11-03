package io.github.raghavsatyadev.phonebackup.shared.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

/**
 * The one NavDisplay both apps use. Screens opened on top (Settings) get their own ViewModels,
 * cleared when they close; the tab ViewModels live above this so switching tabs keeps them.
 */
@Composable
fun AppNavDisplay(backStack: NavBackStack<NavKey>, entries: EntryProviderScope<NavKey>.() -> Unit) {
  NavDisplay(
    backStack = backStack,
    onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
    entryDecorators =
      listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
    entryProvider = entryProvider(builder = entries),
  )
}
