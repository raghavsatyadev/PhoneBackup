@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalTelephotoApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import io.github.raghavsatyadev.phonebackup.shared.data.GalleryClient
import io.github.raghavsatyadev.phonebackup.shared.data.GalleryPhoto
import io.github.raghavsatyadev.phonebackup.shared.data.ServerConfig
import io.github.raghavsatyadev.phonebackup.shared.resources.Res
import io.github.raghavsatyadev.phonebackup.shared.resources.action_back
import io.github.raghavsatyadev.phonebackup.shared.resources.action_info
import io.github.raghavsatyadev.phonebackup.shared.resources.action_next_photo
import io.github.raghavsatyadev.phonebackup.shared.resources.action_previous
import io.github.raghavsatyadev.phonebackup.shared.resources.action_zoom_in
import io.github.raghavsatyadev.phonebackup.shared.resources.action_zoom_out
import io.github.raghavsatyadev.phonebackup.shared.resources.action_zoom_reset
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_arrow_back
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_chevron_left
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_chevron_right
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_fit_screen
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_info
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_zoom_in
import io.github.raghavsatyadev.phonebackup.shared.resources.ic_zoom_out
import io.github.raghavsatyadev.phonebackup.shared.ui.isDesktop
import kotlinx.coroutines.launch
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val ZoomStep = 1.5f

/**
 * Full-screen photo viewer in the style of Google Photos: swipe for the next photo, pinch or
 * double-tap to zoom, swipe down to close, tap to hide the bars. Desktop gets buttons and keys
 * (arrows, + / - / 0, I, Esc) for the same controls.
 */
@Composable
fun PhotoViewer(state: LibraryUiState, viewer: ViewerState, onEvent: (LibraryEvent) -> Unit) {
  val byId = state.photos.associateBy { it.id }
  val photos = viewer.ids.mapNotNull { byId[it] }
  // A new ViewerState (opening similar shots) starts a new pager at its own index.
  val pager = key(viewer) {
    rememberPagerState(initialPage = viewer.index.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
  }
  val scope = rememberCoroutineScope()
  val zooms = remember { mutableStateMapOf<String, ZoomableState>() }
  var bars by rememberSaveable { mutableStateOf(true) }
  var info by rememberSaveable { mutableStateOf(isDesktop) }
  val current = photos.getOrNull(pager.currentPage)
  val controls =
    ViewerControls(
      back = { onEvent(LibraryEvent.Back) },
      go = { delta -> scope.launch { pager.animateScrollToPage((pager.currentPage + delta).coerceIn(0, photos.lastIndex)) } },
      zoom = { factor ->
        val z = current?.let { zooms[it.id] }
        if (z != null) scope.launch { if (factor == 0f) z.resetZoom() else z.zoomBy(factor) }
      },
      toggleInfo = { info = !info },
    )
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  BoxWithConstraints(
    Modifier.fillMaxSize()
      .background(Color.Black)
      .focusRequester(focus)
      .focusable()
      .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && controls.onKey(e.key) }
  ) {
    val stage: @Composable (Modifier) -> Unit = { m ->
      Stage(state.config, photos, pager, zooms, current, bars, controls, onTap = { bars = !bars }, modifier = m)
    }
    val detailsShown = info && bars && current != null
    if (maxWidth > 840.dp) {
      Row(Modifier.fillMaxSize()) {
        stage(Modifier.weight(1f).fillMaxHeight())
        if (detailsShown) {
          DetailsPanel(Modifier.width(380.dp).fillMaxHeight()) { PhotoDetails(state, current!!, viewer, onEvent, Modifier) }
        }
      }
    } else {
      Column(Modifier.fillMaxSize()) {
        stage(Modifier.weight(1f).fillMaxWidth())
        if (detailsShown) {
          DetailsPanel(Modifier.fillMaxWidth().heightIn(max = this@BoxWithConstraints.maxHeight * 0.45f)) {
            PhotoDetails(state, current!!, viewer, onEvent, Modifier)
          }
        }
      }
    }
  }
}

/** Actions shared by the top bar, the side arrows and the keyboard. zoom(0f) resets. */
private class ViewerControls(
  val back: () -> Unit,
  val go: (Int) -> Unit,
  val zoom: (Float) -> Unit,
  val toggleInfo: () -> Unit,
) {
  fun onKey(key: Key): Boolean {
    when (key) {
      Key.DirectionLeft, Key.PageUp -> go(-1)
      Key.DirectionRight, Key.PageDown -> go(1)
      Key.Plus, Key.Equals, Key.NumPadAdd -> zoom(ZoomStep)
      Key.Minus, Key.NumPadSubtract -> zoom(1f / ZoomStep)
      Key.Zero, Key.NumPad0 -> zoom(0f)
      Key.I -> toggleInfo()
      Key.Escape -> back()
      else -> return false
    }
    return true
  }
}

@Composable
private fun DetailsPanel(modifier: Modifier, content: @Composable () -> Unit) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Box(Modifier.verticalScroll(rememberScrollState())) { content() }
  }
}

/** The photo pager with the top bar and (on desktop) side arrows laid over it. */
@Composable
private fun Stage(
  config: ServerConfig,
  photos: List<GalleryPhoto>,
  pager: PagerState,
  zooms: SnapshotStateMap<String, ZoomableState>,
  current: GalleryPhoto?,
  bars: Boolean,
  controls: ViewerControls,
  onTap: () -> Unit,
  modifier: Modifier,
) {
  Box(modifier) {
    HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].id }, beyondViewportPageCount = 1) { page ->
      ZoomablePhoto(config, photos[page], zooms, onTap = onTap, onDismiss = controls.back)
    }
    AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
      ViewerTopBar(current, pager, photos.size, controls)
    }
    if (isDesktop && bars) {
      if (pager.currentPage > 0) {
        SideArrow(Modifier.align(Alignment.CenterStart), Res.drawable.ic_chevron_left, Res.string.action_previous) { controls.go(-1) }
      }
      if (pager.currentPage < photos.size - 1) {
        SideArrow(Modifier.align(Alignment.CenterEnd), Res.drawable.ic_chevron_right, Res.string.action_next_photo) { controls.go(1) }
      }
    }
  }
}

@Composable
private fun ViewerTopBar(current: GalleryPhoto?, pager: PagerState, count: Int, controls: ViewerControls) {
  TopAppBar(
    title = { Text(current?.fileName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    navigationIcon = { BarButton(Res.drawable.ic_arrow_back, Res.string.action_back, onClick = controls.back) },
    actions = {
      if (isDesktop) {
        BarButton(Res.drawable.ic_zoom_out, Res.string.action_zoom_out) { controls.zoom(1f / ZoomStep) }
        BarButton(Res.drawable.ic_fit_screen, Res.string.action_zoom_reset) { controls.zoom(0f) }
        BarButton(Res.drawable.ic_zoom_in, Res.string.action_zoom_in) { controls.zoom(ZoomStep) }
        BarButton(Res.drawable.ic_chevron_left, Res.string.action_previous, enabled = pager.currentPage > 0) { controls.go(-1) }
        BarButton(Res.drawable.ic_chevron_right, Res.string.action_next_photo, enabled = pager.currentPage < count - 1) {
          controls.go(1)
        }
      }
      BarButton(Res.drawable.ic_info, Res.string.action_info, onClick = controls.toggleInfo)
    },
    colors =
      TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Black.copy(alpha = 0.45f),
        titleContentColor = Color.White,
        navigationIconContentColor = Color.White,
        actionIconContentColor = Color.White,
      ),
  )
}

@Composable
private fun BarButton(icon: DrawableResource, label: StringResource, enabled: Boolean = true, onClick: () -> Unit) {
  IconButton(onClick = onClick, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
    Icon(painterResource(icon), stringResource(label))
  }
}

@Composable
private fun SideArrow(modifier: Modifier, icon: DrawableResource, label: StringResource, onClick: () -> Unit) {
  FilledTonalIconButton(onClick = onClick, modifier = modifier.padding(12.dp), shapes = IconButtonDefaults.shapes()) {
    Icon(painterResource(icon), stringResource(label))
  }
}

/** One page: the large preview, zoomable and flickable. Registers its zoom state for the buttons. */
@Composable
private fun ZoomablePhoto(
  config: ServerConfig,
  photo: GalleryPhoto,
  zooms: SnapshotStateMap<String, ZoomableState>,
  onTap: () -> Unit,
  onDismiss: () -> Unit,
) {
  val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f))
  DisposableEffect(photo.id, zoom) {
    zooms[photo.id] = zoom
    onDispose { if (zooms[photo.id] === zoom) zooms.remove(photo.id) }
  }
  val painter = rememberAsyncImagePainter(GalleryClient.thumbUrl(config, photo.id, large = true))
  val loaded by painter.state.collectAsState()
  val image: Painter? = (loaded as? AsyncImagePainter.State.Success)?.painter
  LaunchedEffect(image) {
    val size = image?.intrinsicSize
    if (size != null && size.isSpecified) zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(size))
  }
  val flick = rememberFlickToDismissState()
  if (flick.gestureState is FlickToDismissState.GestureState.Dismissed) LaunchedEffect(Unit) { onDismiss() }
  FlickToDismiss(state = flick, enabled = (zoom.zoomFraction ?: 0f) == 0f) {
    Image(
      painter = painter,
      contentDescription = photo.caption.ifBlank { photo.fileName },
      // Inside, not Fit: zoomable() scales the photo to fit itself.
      contentScale = ContentScale.Inside,
      modifier = Modifier.fillMaxSize().zoomable(zoom, onClick = { onTap() }),
    )
  }
}
