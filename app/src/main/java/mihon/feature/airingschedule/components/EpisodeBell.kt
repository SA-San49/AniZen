package mihon.feature.airingschedule.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How the user currently wants to be alerted about a show's episodes. */
enum class BellNotifyState { NONE, ONCE, SERIES }

private const val LONG_PRESS_DURATION_MS = 500L
private const val PROGRESS_STEPS = 25

/**
 * Per-anime notification bell shown on the Schedule tab.
 *
 * - Tap: alert once, when this episode's local countdown reaches zero. Tap again to cancel.
 * - Press and hold (0.5 s, ring fills up): alert for every new episode until the series finishes
 *   airing or you hold again to cancel. Keeps working across weekly schedule refreshes.
 *
 * Idle uses a neutral tint, a single alert a strong tint, and "every episode" uses the primary
 * color with the ringing-bell icon so the two active states are easy to tell apart.
 */
@Composable
fun EpisodeBell(
    state: BellNotifyState,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pressProgressState = remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(targetValue = pressProgressState.floatValue, label = "bellPressProgress")
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val haptics = LocalHapticFeedback.current

    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val tint = when (state) {
        BellNotifyState.NONE -> onSurface.copy(alpha = 0.4f)
        BellNotifyState.ONCE -> onSurface.copy(alpha = 0.9f)
        BellNotifyState.SERIES -> primary
    }

    Box(
        modifier = modifier
            .size(36.dp)
            // Keyed on Unit + rememberUpdatedState so recompositions (e.g. the per-minute
            // countdown tick) don't restart the detector mid-press.
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val progressJob = launch {
                            val stepDelay = LONG_PRESS_DURATION_MS / PROGRESS_STEPS
                            for (i in 1..PROGRESS_STEPS) {
                                delay(stepDelay)
                                pressProgressState.floatValue = i / PROGRESS_STEPS.toFloat()
                            }
                        }
                        var cancelled = false
                        val up = withTimeoutOrNull(LONG_PRESS_DURATION_MS) {
                            waitForUpOrCancellation().also { if (it == null) cancelled = true }
                        }
                        progressJob.cancel()
                        pressProgressState.floatValue = 0f
                        when {
                            // Released before the hold threshold: a tap.
                            up != null -> {
                                up.consume()
                                currentOnTap()
                            }
                            // Finger slid off / list started scrolling: do nothing.
                            cancelled -> Unit
                            // Held for the full duration: a long press.
                            else -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnLongPress()
                                // Swallow the eventual release so it isn't treated as a click.
                                waitForUpOrCancellation()?.consume()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when (state) {
                BellNotifyState.NONE -> Icons.Outlined.NotificationsNone
                BellNotifyState.ONCE -> Icons.Filled.Notifications
                BellNotifyState.SERIES -> Icons.Filled.NotificationsActive
            },
            contentDescription = when (state) {
                BellNotifyState.NONE -> "Tap to be notified when this episode airs. Hold to be notified for every episode"
                BellNotifyState.ONCE -> "Notifying for the next episode only. Tap to cancel, hold to notify every episode"
                BellNotifyState.SERIES -> "Notifying every episode until the series finishes. Hold to cancel"
            },
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        if (animatedProgress > 0f) {
            Canvas(modifier = Modifier.size(30.dp)) {
                drawArc(
                    color = primary,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}
