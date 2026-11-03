package io.github.raghavsatyadev.phonebackup.shared.ui

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.github.raghavsatyadev.phonebackup.shared.AppGraph

/**
 * One image loader for the app. The network fetcher is registered by hand (R8 can strip the
 * automatic ServiceLoader lookup) and uses the client that adds the token header.
 */
@Composable
fun AppImageLoader(graph: AppGraph) {
  setSingletonImageLoaderFactory { context ->
    ImageLoader.Builder(context)
      .components { add(KtorNetworkFetcherFactory(httpClient = { graph.imageHttp })) }
      .build()
  }
}
