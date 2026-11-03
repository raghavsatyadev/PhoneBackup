package io.github.raghavsatyadev.phonebackup.shared

import io.github.raghavsatyadev.phonebackup.shared.data.GalleryClient
import io.github.raghavsatyadev.phonebackup.shared.data.NetworkServers
import io.github.raghavsatyadev.phonebackup.shared.data.NoDiscovery
import io.github.raghavsatyadev.phonebackup.shared.data.PairClient
import io.github.raghavsatyadev.phonebackup.shared.data.PhotoSender
import io.github.raghavsatyadev.phonebackup.shared.data.ServerDiscovery
import io.github.raghavsatyadev.phonebackup.shared.data.SettingsStore
import io.github.raghavsatyadev.phonebackup.shared.data.ShareDraft
import io.github.raghavsatyadev.phonebackup.shared.data.ShareQueue
import io.github.raghavsatyadev.phonebackup.shared.data.UploadClient
import io.github.raghavsatyadev.phonebackup.shared.data.authedImageClient
import io.github.raghavsatyadev.phonebackup.shared.data.createHttpClient
import io.github.raghavsatyadev.phonebackup.shared.ui.library.LibraryViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.servers.PairViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.servers.ServersViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.settings.SettingsViewModel
import io.github.raghavsatyadev.phonebackup.shared.ui.share.ShareViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** [name] is what the PC shows when this device asks to pair; [canSearch] is false on the desktop viewer. */
data class DeviceInfo(val name: String, val canSearch: Boolean)

/** The HTTP client the image loader uses for gallery thumbnails; it sends the token in a header. */
val ImageHttp = named("imageHttp")

/** App dependencies, started once by the platform app. Singles are created on first use. */
fun appModule(settings: SettingsStore, discovery: ServerDiscovery = NoDiscovery, deviceName: String = "") =
  module {
    single { settings }
    single { DeviceInfo(deviceName, canSearch = discovery !== NoDiscovery) }
    single { createHttpClient() }
    single(ImageHttp) { authedImageClient(get()) }
    // App-wide scope: work here outlives any screen.
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { UploadClient(get()) }
    single { GalleryClient(get()) }
    single { PairClient(get()) }
    single { NetworkServers(discovery, get()) }
    single {
      val uploads = get<UploadClient>()
      val sender = PhotoSender { config, photo, album, tags, onProgress ->
        uploads
          .upload(config, photo.path, photo.fileName, photo.sizeBytes, album, tags, photo.takenAt) { done, total ->
            if (total > 0) onProgress(done.toFloat() / total)
          }
          .skipped
      }
      ShareQueue(get(), sender) { settings.saveDraft(ShareDraft()) }
    }

    viewModel { ShareViewModel(get(), get()) }
    viewModel { p -> LibraryViewModel(get(), get(), p.get(0)) }
    viewModel { ServersViewModel(get(), get<NetworkServers>().found, get<DeviceInfo>().canSearch) }
    viewModel { p -> SettingsViewModel(get(), get(), p.getOrNull<String>()) }
    viewModel { p -> PairViewModel(get(), get(), get<DeviceInfo>().name, p.get(0), p.get(1)) }
  }
