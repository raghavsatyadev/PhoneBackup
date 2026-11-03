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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
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
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private enum class MainTab { Share, Library }

/** The Android app: Share and Library tabs, plus Settings. */
@Composable
fun App(graph: AppGraph, onPickPhotos: () -> Unit) {
  AppTheme {
    AppImageLoader(graph)
    var showSettings by rememberSaveable { mutableStateOf(!graph.settings.config.value.isComplete) }
    var tab by rememberSaveable { mutableStateOf(MainTab.Share) }
    // Photos shared from another app always land on the Share tab.
    LaunchedEffect(graph.queue) { graph.queue.deliveries.drop(1).collect { tab = MainTab.Share } }
    if (showSettings) {
      Settings(graph) { showSettings = false }
      return@AppTheme
    }
    Scaffold(
      bottomBar = {
        NavigationBar {
          NavigationBarItem(
            selected = tab == MainTab.Share,
            onClick = { tab = MainTab.Share },
            icon = { Icon(painterResource(Res.drawable.ic_cloud_upload), null) },
            label = { Text(stringResource(Res.string.tab_share)) },
          )
          NavigationBarItem(
            selected = tab == MainTab.Library,
            onClick = { tab = MainTab.Library },
            icon = { Icon(painterResource(Res.drawable.ic_photo_library), null) },
            label = { Text(stringResource(Res.string.tab_library)) },
          )
        }
      }
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        when (tab) {
          MainTab.Share -> {
            val vm = viewModel { ShareViewModel(graph.queue, graph.settings) }
            ShareRoute(vm, onPickPhotos = onPickPhotos, onOpenSettings = { showSettings = true })
          }
          MainTab.Library -> Library(graph) { showSettings = true }
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
    var showSettings by rememberSaveable { mutableStateOf(!graph.settings.config.value.isComplete) }
    if (showSettings) Settings(graph) { showSettings = false } else Library(graph) { showSettings = true }
  }
}

@Composable
private fun Settings(graph: AppGraph, onBack: () -> Unit) {
  val vm = viewModel { SettingsViewModel(graph.settings, graph.uploads) }
  SettingsRoute(vm, onBack = onBack)
}

@Composable
private fun Library(graph: AppGraph, onOpenSettings: () -> Unit) {
  val uncategorized = stringResource(Res.string.no_category)
  val vm = viewModel { LibraryViewModel(graph.settings, graph.gallery) { uncategorized } }
  LibraryRoute(vm, onOpenSettings = onOpenSettings)
}
