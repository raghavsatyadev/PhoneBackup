package io.github.raghavsatyadev.phonebackup.shared.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent

/**
 * Screen transitions: a new screen slides in from the end and the old one moves a little the other
 * way (Material shared axis X); Back plays it in reverse. A predictive back swipe shrinks the top
 * screen and pulls it towards the swiped edge while the one below slides back in, following the
 * finger. Tabs fade through instead of sliding (see [TabMetadata]).
 */
object NavTransitions {
  /** Forward navigation. */
  fun <T : Any> push(): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = { forward() }

  /** Back (button, Escape, or a back gesture that was not tracked). */
  fun <T : Any> pop(): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = { backward() }

  /** Going deeper: the new content slides in from the end. */
  fun forward(): ContentTransform =
    (slideInHorizontally(slide) { it / SHIFT } + fadeIn(fadeInSpec)) togetherWith
      (slideOutHorizontally(slide) { -it / SHIFT } + fadeOut(fadeOutSpec))

  /** Going back: [forward] in reverse. */
  fun backward(): ContentTransform =
    (slideInHorizontally(slide) { -it / SHIFT } + fadeIn(fadeInSpec)) togetherWith
      (slideOutHorizontally(slide) { it / SHIFT } + fadeOut(fadeOutSpec))

  /**
   * Predictive back. NavDisplay seeks this transition with the swipe progress, so every part is a
   * linear tween of the same length: the screen moves exactly with the finger.
   */
  fun <T : Any> predictivePop(): AnimatedContentTransitionScope<Scene<T>>.(Int) -> ContentTransform = { edge ->
    predictiveEnter() togetherWith predictiveExit(edge)
  }

  /** The screen underneath during a predictive back: slides back in from the start. */
  fun predictiveEnter(): EnterTransition = slideInHorizontally(seekOffset) { -it / SHIFT } + fadeIn(seekFloat)

  /** The screen being left during a predictive back: shrinks towards the swiped [edge], fading at the end. */
  fun predictiveExit(edge: Int): ExitTransition {
    val toRight = edge != NavigationEvent.EDGE_RIGHT
    return scaleOut(seekFloat, targetScale = 0.9f) +
      slideOutHorizontally(seekOffset) { if (toRight) it / PEEK else -it / PEEK } +
      fadeOut(tween(SEEK_MS / 3, delayMillis = SEEK_MS * 2 / 3, easing = LinearEasing))
  }

  /** Switching between bottom tabs: fade through, no sideways motion. */
  fun fadeThrough(): ContentTransform =
    (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.94f)) togetherWith
      fadeOut(tween(90))

  /** Back gesture on a tab: the tab shrinks and fades while the first tab comes back. */
  fun predictiveFade(): ContentTransform =
    fadeIn(seekFloat) togetherWith (scaleOut(seekFloat, targetScale = 0.9f) + fadeOut(seekFloat))

  /** Metadata for a tab's entry, so moving to or from it fades through instead of sliding. */
  val TabMetadata: Map<String, Any> by lazy {
    NavDisplay.transitionSpec { fadeThrough() } +
      NavDisplay.popTransitionSpec { fadeThrough() } +
      NavDisplay.predictivePopTransitionSpec { predictiveFade() }
  }

  // No bounce: whole screens overshooting looks like a glitch.
  private val slide: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = IntOffset(1, 1))
  private val fadeInSpec = tween<Float>(220, delayMillis = 50)
  private val fadeOutSpec = tween<Float>(150)
  private val seekFloat = tween<Float>(SEEK_MS, easing = LinearEasing)
  private val seekOffset = tween<IntOffset>(SEEK_MS, easing = LinearEasing)

  private const val SEEK_MS = 300
  /** The old screen moves a fifth of the width. */
  private const val SHIFT = 5
  /** How far the top screen moves towards the swiped edge during a predictive back. */
  private const val PEEK = 12
}
