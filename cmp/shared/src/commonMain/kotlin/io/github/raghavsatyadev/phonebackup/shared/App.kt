package io.github.raghavsatyadev.phonebackup.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.settings.SettingsRoute
import io.github.raghavsatyadev.phonebackup.shared.ui.settings.SettingsViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.share.ShareRoute
import io.github.raghavsatyadev.phonebackup.shared.ui.share.ShareViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.theme.AppTheme

@Composable
fun App(graph: AppGraph, onPickPhotos: () -> Unit) {
  AppTheme {
    var showSettings by rememberSaveable { mutableStateOf(!graph.settings.config.value.isComplete) }
    if (showSettings) {
      val vm = viewModel { SettingsViewModel(graph.settings, graph.uploads) }
      SettingsRoute(vm, onBack = { showSettings = false })
    } else {
      val vm = viewModel { ShareViewModel(graph.inbox, graph.settings, graph.uploads) }
      ShareRoute(vm, onPickPhotos = onPickPhotos, onOpenSettings = { showSettings = true })
    }
  }
}
