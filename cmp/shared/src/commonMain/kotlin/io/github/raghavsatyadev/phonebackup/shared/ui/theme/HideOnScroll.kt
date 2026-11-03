package io.github.raghavsatyadev.phonebackup.shared.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * Hides a bar while the content scrolls down and shows it again on scroll up.
 * Attach [connection] with `Modifier.nestedScroll` on a parent of the scrolling list.
 */
@Stable
class HideOnScroll {
  var visible by mutableStateOf(true)
    private set

  val connection =
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y < -1f) visible = false else if (available.y > 1f) visible = true
        return Offset.Zero
      }
    }
}

@Composable fun rememberHideOnScroll(): HideOnScroll = remember { HideOnScroll() }

/** Slides [content] away when [visible] is false. */
@Composable
fun ScrollAwayBar(visible: Boolean, content: @Composable () -> Unit) {
  AnimatedVisibility(
    visible = visible,
    enter = expandVertically(expandFrom = Alignment.Top),
    exit = shrinkVertically(shrinkTowards = Alignment.Top),
  ) {
    content()
  }
}
