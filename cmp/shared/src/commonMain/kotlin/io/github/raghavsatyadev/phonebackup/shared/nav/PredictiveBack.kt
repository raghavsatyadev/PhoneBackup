package io.github.raghavsatyadev.phonebackup.shared.nav

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState.InProgress
import androidx.navigationevent.compose.NavigationEventState

/**
 * A [Transition] to [target] that a back swipe can drag towards [back], for in-screen Back (closing
 * the photo viewer or an album) that should move with the finger like NavDisplay's own screens.
 * Pair it with a NavigationBackHandler using the same [backState]; once Back completes, the caller
 * sets [target] to [back] and the transition finishes from where the finger left it. A cancelled
 * swipe springs back. A swipe is ignored while [enabled] is false (another layer answers it).
 */
class PredictiveBack<T>(val transition: Transition<T>, private val gesture: () -> Boolean, private val edge: () -> Int) {
  /** True while this close started from a back swipe (use the predictive exit, not the normal one). */
  val byGesture: Boolean get() = gesture()

  /** The edge the swipe started from ([NavigationEvent.EDGE_LEFT] or [NavigationEvent.EDGE_RIGHT]). */
  val swipeEdge: Int get() = edge()
}

@Composable
fun <T> rememberPredictiveBack(
  target: T,
  back: T,
  backState: NavigationEventState<*>,
  enabled: Boolean = true,
): PredictiveBack<T> {
  val seek = remember { SeekableTransitionState(target) }
  var byGesture by remember { mutableStateOf(false) }
  var edge by remember { mutableIntStateOf(NavigationEvent.EDGE_LEFT) }
  val event = (backState.transitionState as? InProgress)?.latestEvent
  val progress = if (enabled && target != back) event?.progress else null
  val tracking = progress != null
  if (event != null && tracking) {
    byGesture = true
    edge = event.swipeEdge
  } else if (target != back) {
    byGesture = false
  }
  LaunchedEffect(target, progress) {
    if (progress != null) seek.seekTo(progress, back) else seek.animateTo(target)
  }
  val transition = rememberTransition(seek, label = "predictive-back")
  return remember(transition) { PredictiveBack(transition, { byGesture }, { edge }) }
}
