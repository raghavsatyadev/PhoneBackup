@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.raghavsatyadev.phonebackup.shared.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * False when the device has animations off. Compose animations end at once by themselves then; this
 * is for motion Compose does not time, such as an endless loop or the moving wave of a wavy bar.
 */
@Composable
fun animationsEnabled(): Boolean {
  val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
  return scale > 0f
}

/**
 * The screen's one decorative motion: a Material shape turning slowly (one turn a minute) behind
 * [content]. Stands still when animations are off.
 */
@Composable
fun DriftingShape(modifier: Modifier = Modifier, size: Dp = 160.dp, content: @Composable () -> Unit) {
  val moving = animationsEnabled()
  val turn by
    rememberInfiniteTransition(label = "drift")
      .animateFloat(
        initialValue = 0f,
        targetValue = if (moving) 360f else 0f,
        animationSpec = infiniteRepeatable(tween(DRIFT_PERIOD_MILLIS, easing = LinearEasing)),
        label = "turn",
      )
  val shape = MaterialShapes.Cookie9Sided.toShape()
  Box(modifier.size(size), contentAlignment = Alignment.Center) {
    Box(
      Modifier.size(size)
        .graphicsLayer { rotationZ = turn }
        .background(MaterialTheme.colorScheme.primaryContainer, shape)
    )
    content()
  }
}

/** A loading indicator that shows only after [delayMillis], so a quick wait does not flash. */
@Composable
fun DelayedLoadingIndicator(modifier: Modifier = Modifier, delayMillis: Long = 400) {
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    delay(delayMillis)
    visible = true
  }
  if (visible) LoadingIndicator(modifier) else Box(modifier)
}

private const val DRIFT_PERIOD_MILLIS = 60_000
