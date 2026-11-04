@file:OptIn(KoinExperimentalAPI::class)

package io.github.raghavsatyadev.phonebackup.shared.backup

import io.github.raghavsatyadev.phonebackup.shared.LocalAppNav
import io.github.raghavsatyadev.phonebackup.shared.backup.data.BackupApi
import io.github.raghavsatyadev.phonebackup.shared.backup.data.TransferFeed
import io.github.raghavsatyadev.phonebackup.shared.nav.BackupKey
import io.github.raghavsatyadev.phonebackup.shared.nav.PhoneFoldersKey
import io.github.raghavsatyadev.phonebackup.shared.nav.TransferKey
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/**
 * The Backup tab (desktop only): phone-to-PC backups run by the bundled server on this PC.
 * [pickFolder] chooses the PC folder to back up into, [pickStatusFile] a saved
 * `phonebackup_status.json` to carry on with; either may be null (the field still takes a path).
 */
fun backupModule(pickFolder: (suspend (String) -> String?)?, pickStatusFile: (suspend () -> String?)?) = module {
  single { BackupApi() }
  single { TransferFeed(get(), get()) }
  // A single, not a viewModel: the form and the live list outlive switching tabs.
  single { BackupViewModel(get(), get(), pickFolder, pickStatusFile) }
  viewModel { (serial: String) -> PhoneFoldersViewModel(get(), serial) }
  viewModel { (id: String) -> TransferViewModel(id, get(), get()) }

  navigation<BackupKey> {
    val backStack = LocalAppNav.current.backStack
    BackupRoute(
      koinInject(),
      BackupNav(
        onBrowsePhone = { backStack.add(PhoneFoldersKey(it)) },
        onOpenTransfer = { backStack.add(TransferKey(it)) },
      ),
    )
  }
  navigation<PhoneFoldersKey> { key ->
    val backStack = LocalAppNav.current.backStack
    val backup = koinInject<BackupViewModel>()
    PhoneFoldersRoute(
      koinViewModel<PhoneFoldersViewModel> { parametersOf(key.serial) },
      onBack = { backStack.removeLastOrNull() },
      onAdd = {
        backup.onEvent(BackupEvent.AddSources(it))
        backStack.removeLastOrNull()
      },
    )
  }
  navigation<TransferKey> { key ->
    val backStack = LocalAppNav.current.backStack
    val backup = koinInject<BackupViewModel>()
    TransferRoute(
      koinViewModel<TransferViewModel> { parametersOf(key.id) },
      rememberConfirmingActions(rememberTransferActions(backup::onEvent)),
      onBack = { backStack.removeLastOrNull() },
    )
  }
}
