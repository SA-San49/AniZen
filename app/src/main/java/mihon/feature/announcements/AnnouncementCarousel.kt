package mihon.feature.announcements

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.absoluteValue

/**
 * Picks the carousel entries from the already filtered list and keeps them correct over time.
 *
 * One coroutine, started only while the screen is STARTED, sleeps until the next release-date
 * transition or grace-period expiry and then recomputes. It restarts whenever the dataset or
 * filters change and recalculates from the wall clock every time the screen becomes active.
 * No network access and no polling.
 */
@Composable
internal fun rememberCarouselEntries(
    entries: List<AnnouncementEntry>,
    enabled: Boolean,
): List<AnnouncementEntry> {
    val lifecycleOwner = LocalLifecycleOwner.current
    var snapshot by remember(entries, enabled) {
        mutableStateOf(
            CarouselSnapshot(
                entries = if (enabled) selectCarouselEntries(entries, System.currentTimeMillis()) else emptyList(),
                evaluatedAt = System.currentTimeMillis(),
            ),
        )
    }

    LaunchedEffect(entries, enabled, lifecycleOwner) {
        if (!enabled) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                val current = selectCarouselEntries(entries, now)
                // evaluatedAt changes even if the selected entries are equal, so date labels
                // (for example "In 1m" -> "Out now") refresh at the scheduled release boundary.
                snapshot = CarouselSnapshot(entries = current, evaluatedAt = now)
                val nextUpdate = nextCarouselUpdate(current, now) ?: break
                delay((nextUpdate - now).coerceIn(CAROUSEL_MIN_WAIT_MS, CAROUSEL_MAX_WAIT_MS))
            }
        }
    }
    return snapshot.entries
}

private data class CarouselSnapshot(
    val entries: List<AnnouncementEntry>,
    val evaluatedAt: Long,
)

private class CarouselSelection {
    var id: Int? = null
    var index: Int = 0
}

/**
 * Swipeable, auto-rotating version of the "Up next" hero. Each page is the existing
 * [FeaturedAnnouncementCard]; only visible pages are composed.
 */
@Composable
internal fun FeaturedAnnouncementCarousel(
    entries: List<AnnouncementEntry>,
    onEntryClick: (AnnouncementEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = entries.size
    if (size == 0) return

    val pagerState = rememberPagerState(initialPage = carouselStartPage(size)) { carouselPageCount(size) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestEntries by rememberUpdatedState(entries)
    val currentSize by rememberUpdatedState(size)
    val selection = remember { CarouselSelection() }
    var isHeld by remember { mutableStateOf(false) }
    var timerEpoch by remember { mutableIntStateOf(0) }
    val rotatable = size > 1

    // Remember which anime is on screen (by id) so dataset changes can keep it selected.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val list = latestEntries
            if (list.isNotEmpty()) {
                val index = carouselIndexForPage(page, list.size)
                selection.index = index
                selection.id = list[index].mediaId
            }
        }
    }

    // Reconcile the visible page with a changed dataset.
    LaunchedEffect(entries) {
        val previousId = selection.id
        val current = pagerState.currentPage
        val targetIndex = if (previousId == null) {
            carouselIndexForPage(current, size)
        } else {
            reconcileCarouselIndex(previousId, selection.index, entries)
        }
        val target = carouselTargetPage(current, size, targetIndex)
        if (target != current) pagerState.scrollToPage(target)
        if (previousId != null && entries.none { it.mediaId == previousId }) {
            // The selected anime expired: a valid neighbour is shown now, start a fresh interval.
            timerEpoch += 1
        }
    }

    // The single rotation loop. Touches, swipes and long presses all push the next advance
    // a full interval into the future; it never runs while the screen is not STARTED.
    LaunchedEffect(pagerState, rotatable, lifecycleOwner) {
        if (!rotatable) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // Wait for finger-up and for any swipe / fling to settle.
                snapshotFlow { !isHeld && !pagerState.isScrollInProgress }.first { it }
                val epochAtStart = timerEpoch
                val interrupted = withTimeoutOrNull(CAROUSEL_INTERVAL_MS) {
                    snapshotFlow {
                        isHeld || pagerState.isScrollInProgress || timerEpoch != epochAtStart
                    }.first { it }
                } != null
                if (!interrupted) advanceCarousel(pagerState, currentSize)
            }
        }
    }

    Column(
        modifier = modifier.pointerInput(Unit) {
            // Observe only (Initial pass, nothing consumed) so taps, long presses and the
            // pager's own drag handling keep working exactly as before.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                isHeld = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    timerEpoch += 1
                } finally {
                    isHeld = false
                }
            }
        },
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = rotatable,
            // Peek the neighbouring heroes so the carousel reads as swipeable at a glance.
            contentPadding = PaddingValues(horizontal = if (rotatable) 20.dp else 16.dp),
            pageSpacing = 10.dp,
        ) { page ->
            val entry = entries.getOrNull(carouselIndexForPage(page, entries.size))
            if (entry != null) {
                FeaturedAnnouncementCard(
                    entry = entry,
                    onClick = { onEntryClick(entry) },
                    modifier = Modifier.graphicsLayer {
                        // Read in the draw layer only: swiping never recomposes the cards.
                        val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                            .absoluteValue
                            .coerceIn(0f, 1f)
                        val scale = 1f - 0.08f * offset
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.4f * offset
                    },
                )
            }
        }
        if (rotatable) {
            CarouselDots(
                pagerState = pagerState,
                size = size,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp),
            )
        }
    }
}

private suspend fun advanceCarousel(pagerState: PagerState, size: Int) {
    if (size <= 1) return
    val next = pagerState.currentPage + 1
    try {
        if (next >= CAROUSEL_VIRTUAL_PAGES - CAROUSEL_RECENTER_MARGIN) {
            pagerState.scrollToPage(carouselStartPage(size, next.mod(size)))
        } else {
            pagerState.animateScrollToPage(next)
        }
    } catch (e: CancellationException) {
        // The user grabbed the pager mid-animation, which interrupts ours. Only propagate
        // if this coroutine itself was cancelled (screen left / disposed).
        currentCoroutineContext().ensureActive()
    }
}

@Composable
private fun CarouselDots(
    pagerState: PagerState,
    size: Int,
    modifier: Modifier = Modifier,
) {
    // Derived so only the dots recompose when the page changes.
    val position by remember(pagerState, size) {
        derivedStateOf { carouselIndexForPage(pagerState.currentPage, size) }
    }
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(size) { index ->
            val selected = index == position
            val width by animateDpAsState(
                targetValue = if (selected) 22.dp else 6.dp,
                label = "carouselDotWidth",
            )
            val color by animateColorAsState(
                targetValue = if (selected) active else inactive,
                label = "carouselDotColor",
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = 6.dp)
                    .background(color, CircleShape),
            )
        }
    }
}
