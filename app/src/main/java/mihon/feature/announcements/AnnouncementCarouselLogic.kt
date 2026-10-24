package mihon.feature.announcements

/*
 * Pure logic behind the Announcements hero carousel, kept separate from Compose so it can be
 * unit tested on the JVM.
 *
 * Eligibility is a pure function of the entry's release timestamp and current time, so an anime
 * past its grace period cannot come back after reopening the screen or restarting the app. The
 * announcements cache is pruned using the same 24-hour rule in AnnouncementsRepository.
 */

internal const val CAROUSEL_MAX_ITEMS = 7
internal const val CAROUSEL_INTERVAL_MS = 3_000L

/** How long an anime stays in the carousel after its (UTC) release timestamp. */
internal const val CAROUSEL_GRACE_MS = 24 * 60 * 60 * 1000L

/** Bounds for the update timer so wall-clock changes / deep sleep cannot leave stale entries. */
internal const val CAROUSEL_MIN_WAIT_MS = 250L
internal const val CAROUSEL_MAX_WAIT_MS = 30 * 60 * 1000L

/** Virtual page count used to make the pager wrap around in both directions. */
internal const val CAROUSEL_VIRTUAL_PAGES = 100_000
internal const val CAROUSEL_RECENTER_MARGIN = 1_000

internal fun AnnouncementEntry.isCarouselEligible(now: Long): Boolean =
    exactReleaseDate?.let { it + CAROUSEL_GRACE_MS > now } == true

/**
 * Up to [maxItems] nearest eligible entries, earliest release first.
 * [entries] should already have the user's filters applied.
 */
internal fun selectCarouselEntries(
    entries: List<AnnouncementEntry>,
    now: Long,
    maxItems: Int = CAROUSEL_MAX_ITEMS,
): List<AnnouncementEntry> {
    if (entries.isEmpty()) return emptyList()
    return entries
        .asSequence()
        .filter { it.isCarouselEligible(now) }
        .distinctBy { it.mediaId }
        .sortedWith(
            compareBy<AnnouncementEntry> { it.exactReleaseDate ?: Long.MAX_VALUE }
                .thenBy { it.title.lowercase() },
        )
        .take(maxItems)
        .toList()
}

/**
 * Next date boundary that can change a displayed countdown or carousel eligibility.
 * Future releases change the banner label; release + 24 hours removes the anime.
 */
internal fun nextCarouselUpdate(carousel: List<AnnouncementEntry>, now: Long): Long? =
    carousel.asSequence()
        .mapNotNull { it.exactReleaseDate }
        .flatMap { sequenceOf(it, it + CAROUSEL_GRACE_MS) }
        .filter { it > now }
        .minOrNull()

/** Index to show after the list changed: keep the selected anime if it is still there. */
internal fun reconcileCarouselIndex(
    selectedId: Int?,
    selectedIndex: Int,
    entries: List<AnnouncementEntry>,
): Int {
    if (entries.isEmpty()) return 0
    val byId = selectedId?.let { id -> entries.indexOfFirst { it.mediaId == id } } ?: -1
    return if (byId >= 0) byId else selectedIndex.coerceIn(0, entries.lastIndex)
}

internal fun carouselPageCount(size: Int): Int = if (size > 1) CAROUSEL_VIRTUAL_PAGES else size.coerceAtLeast(0)

/** Page (aligned to a multiple of [size]) near the middle of the virtual range, plus [index]. */
internal fun carouselStartPage(size: Int, index: Int = 0): Int {
    if (size <= 1) return 0
    val half = CAROUSEL_VIRTUAL_PAGES / 2
    return half - half % size + index
}

internal fun carouselIndexForPage(page: Int, size: Int): Int = if (size <= 0) 0 else page.mod(size)

/** Page showing [index] that is closest to [current], re-centering when [current] nears either edge. */
internal fun carouselTargetPage(current: Int, size: Int, index: Int): Int {
    if (size <= 1) return 0
    val inSafeRange = current in CAROUSEL_RECENTER_MARGIN..(CAROUSEL_VIRTUAL_PAGES - CAROUSEL_RECENTER_MARGIN)
    val base = if (inSafeRange) current - current.mod(size) else carouselStartPage(size, 0)
    return base + index
}
