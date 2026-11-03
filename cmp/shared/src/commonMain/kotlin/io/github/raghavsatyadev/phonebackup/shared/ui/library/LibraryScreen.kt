@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.raghavsatyadev.phonebackup.shared.data.GalleryClient
import io.github.raghavsatyadev.phonebackup.shared.data.GalleryPhoto
import io.github.raghavsatyadev.phonebackup.shared.data.ServerConfig
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_back
import io.github.raghavsatyadev.phonebackup.shared.resources.action_refresh
import io.github.raghavsatyadev.phonebackup.shared.resources.action_retry
import io.github.raghavsatyadev.phonebackup.shared.resources.action_settings
import io.github.raghavsatyadev.phonebackup.shared.resources.bucket_count
import io.github.raghavsatyadev.phonebackup.shared.resources.hide_similar
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_arrow_back
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_burst
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_play
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_refresh
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_settings
import io.github.raghavsatyadev.phonebackup.shared.resources.library_empty
import io.github.raghavsatyadev.phonebackup.shared.resources.library_error
import io.github.raghavsatyadev.phonebackup.shared.resources.library_title
import io.github.raghavsatyadev.phonebackup.shared.resources.no_category
import io.github.raghavsatyadev.phonebackup.shared.resources.video
import io.github.raghavsatyadev.phonebackup.shared.resources.view_albums
import io.github.raghavsatyadev.phonebackup.shared.resources.view_all
import io.github.raghavsatyadev.phonebackup.shared.resources.view_categories
import io.github.raghavsatyadev.phonebackup.shared.resources.view_events
import io.github.raghavsatyadev.phonebackup.shared.ui.BackHandler
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryRoute(viewModel: LibraryViewModel, onOpenSettings: () -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val onEvent = viewModel::onEvent
  if (state.viewer != null || state.bucket != null) BackHandler { onEvent(LibraryEvent.Back) }
  val viewer = state.viewer
  if (viewer != null) {
    PhotoViewer(state, viewer, onEvent)
  } else {
    LibraryScreen(state, onEvent, onOpenSettings)
  }
}

@Composable
fun LibraryScreen(state: LibraryUiState, onEvent: (LibraryEvent) -> Unit, onOpenSettings: () -> Unit) {
  val uncategorized = stringResource(Res.string.no_category)
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(state.bucket ?: stringResource(Res.string.library_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
          if (state.bucket != null) {
            IconButton(onClick = { onEvent(LibraryEvent.Back) }, shapes = IconButtonDefaults.shapes()) {
              Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.action_back))
            }
          }
        },
        actions = {
          IconButton(onClick = { onEvent(LibraryEvent.Refresh) }, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_refresh), stringResource(Res.string.action_refresh))
          }
          IconButton(onClick = onOpenSettings, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_settings), stringResource(Res.string.action_settings))
          }
        },
      )
    }
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      if (state.bucket == null) ViewTabs(state.view) { onEvent(LibraryEvent.SelectView(it)) }
      Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
          selected = state.hideSimilar,
          onClick = { onEvent(LibraryEvent.ToggleHideSimilar) },
          label = { Text(stringResource(Res.string.hide_similar)) },
          leadingIcon = { Icon(painterResource(Res.drawable.ic_burst), null, Modifier.size(18.dp)) },
        )
        if (state.loading) LoadingIndicator(Modifier.padding(start = 12.dp).size(32.dp))
      }
      val error = state.error
      when {
        error != null && state.photos.isEmpty() ->
          Message(stringResource(Res.string.library_error, error)) {
            Button(onClick = { onEvent(LibraryEvent.Refresh) }, shapes = ButtonDefaults.shapes()) {
              Text(stringResource(Res.string.action_retry))
            }
          }
        state.photos.isEmpty() && !state.loading -> Message(stringResource(Res.string.library_empty)) {}
        state.view != LibraryView.All && state.bucket == null ->
          BucketGrid(state.config, state.buckets(uncategorized)) { onEvent(LibraryEvent.OpenBucket(it)) }
        else -> PhotoGrid(state, state.gridPhotos(uncategorized), onEvent)
      }
    }
  }
}

@Composable
private fun ViewTabs(selected: LibraryView, onSelect: (LibraryView) -> Unit) {
  val labels =
    mapOf(
      LibraryView.All to Res.string.view_all,
      LibraryView.Albums to Res.string.view_albums,
      LibraryView.Categories to Res.string.view_categories,
      LibraryView.Events to Res.string.view_events,
    )
  PrimaryTabRow(selectedTabIndex = selected.ordinal) {
    LibraryView.entries.forEach { v ->
      Tab(selected = v == selected, onClick = { onSelect(v) }, text = { Text(stringResource(labels.getValue(v))) })
    }
  }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit) {
  Column(
    Modifier.fillMaxWidth().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    action()
  }
}

@Composable
private fun BucketGrid(config: ServerConfig, buckets: List<Bucket>, onOpen: (String) -> Unit) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 160.dp),
    contentPadding = PaddingValues(16.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(buckets, key = { it.name }) { b ->
      Card(onClick = { onOpen(b.name) }, shape = MaterialTheme.shapes.large) {
        Thumb(config, b.cover, Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.padding(12.dp)) {
          Text(b.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(
            stringResource(Res.string.bucket_count, b.count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun PhotoGrid(state: LibraryUiState, photos: List<GalleryPhoto>, onEvent: (LibraryEvent) -> Unit) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 112.dp),
    contentPadding = PaddingValues(4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    items(photos, key = { it.id }) { p ->
      Box(Modifier.aspectRatio(1f).clickable { onEvent(LibraryEvent.Open(p.id)) }) {
        Thumb(state.config, p, Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          if (p.isVideo) {
            Badge { Icon(painterResource(Res.drawable.ic_play), stringResource(Res.string.video), Modifier.size(14.dp)) }
          }
          if (state.hideSimilar && p.groupSize > 1) {
            Badge {
              Icon(painterResource(Res.drawable.ic_burst), null, Modifier.size(14.dp))
              Text("${p.groupSize}", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Badge(content: @Composable () -> Unit) {
  Surface(shape = MaterialTheme.shapes.small, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White) {
    Row(
      Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      content()
    }
  }
}

/** A server preview: small and cropped for grids, large and whole for the viewer. */
@Composable
fun Thumb(config: ServerConfig, photo: GalleryPhoto, modifier: Modifier = Modifier, large: Boolean = false) {
  AsyncImage(
    model = GalleryClient.thumbUrl(config, photo.id, large),
    contentDescription = photo.caption.ifBlank { photo.fileName },
    contentScale = if (large) ContentScale.Fit else ContentScale.Crop,
    modifier = modifier,
  )
}
