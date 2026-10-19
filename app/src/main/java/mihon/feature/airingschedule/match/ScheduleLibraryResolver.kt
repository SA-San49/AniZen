package mihon.feature.airingschedule.match

import mihon.feature.airingschedule.AiringScheduleEntry
import mihon.feature.airingschedule.util.ScheduleDeepTitleMatcher
import mihon.feature.airingschedule.util.ScheduleTitleMatcher

/** A library anime as the schedule sees it. */
data class ScheduleLibraryRef(
    val animeId: Long,
    val sourceId: Long,
    val url: String,
    val titles: List<String>,
)

/** Which anime a schedule entry opens, and why. */
data class ScheduleLibraryMatch(
    /** Anime to open when the entry is tapped. */
    val animeId: Long,
    /** Sources this entry is confirmed on (used for favourite-source filtering and upload delay). */
    val sourceIds: Set<Long>,
    /** Whether that anime is currently in the library. */
    val inLibrary: Boolean,
    /** True when this comes from the user's manual pick rather than title matching. */
    val isManual: Boolean,
    val manual: ScheduleManualMatch? = null,
)

/**
 * Resolves schedule entries to library anime.
 *
 * Priority:
 *  1. The user's manual pick from Global Search ([ScheduleManualMatch]) — always wins.
 *  2. Automatic library title match via [ScheduleDeepTitleMatcher.Index]
 *     (normalized exact keys -> deep exact keys -> strict fuzzy similarity).
 *
 * Build one instance per library / manual-match change (off the main thread). Results are
 * cached per schedule media id, so re-filtering or recomposing never repeats the work.
 */
class ScheduleLibraryResolver(
    library: List<ScheduleLibraryRef>,
    private val manualMatches: ScheduleManualMatchIndex = ScheduleManualMatchIndex.EMPTY,
    pinnedSourceIds: List<Long> = emptyList(),
) {
    private val bySourceUrl: Map<String, ScheduleLibraryRef> =
        library.associateBy { sourceUrlKey(it.sourceId, it.url) }
    private val index = ScheduleDeepTitleMatcher.Index(library.map { it to it.titles })
    private val sourcePriority: Map<Long, Int> =
        pinnedSourceIds.withIndex().associate { (i, id) -> id to i }
    private val cache = HashMap<String, Optional>()

    private class Optional(val value: ScheduleLibraryMatch?)

    val librarySize: Int get() = index.size

    @Synchronized
    fun resolve(entry: AiringScheduleEntry): ScheduleLibraryMatch? {
        val cacheKey = "${entry.mediaId}|${entry.titleUserPreferred}"
        cache[cacheKey]?.let { return it.value }
        val result = resolveUncached(entry)
        cache[cacheKey] = Optional(result)
        return result
    }

    fun resolveAll(entries: Collection<AiringScheduleEntry>): Map<Int, ScheduleLibraryMatch> {
        val out = HashMap<Int, ScheduleLibraryMatch>()
        for (entry in entries) {
            if (entry.mediaId in out) continue
            resolve(entry)?.let { out[entry.mediaId] = it }
        }
        return out
    }

    private fun resolveUncached(entry: AiringScheduleEntry): ScheduleLibraryMatch? {
        // 1. Manual pick: highest priority, overrides any title match.
        manualMatches.find(entry)?.let { manual ->
            val lib = bySourceUrl[sourceUrlKey(manual.sourceId, manual.url)]
            return ScheduleLibraryMatch(
                animeId = lib?.animeId ?: manual.animeId,
                sourceIds = setOf(manual.sourceId),
                inLibrary = lib != null,
                isManual = true,
                manual = manual,
            )
        }

        // 2. Automatic library match.
        val found = index.find(ScheduleTitleMatcher.candidateTitlesFromEntry(entry)) ?: return null
        val primary = found.items.minByOrNull { sourcePriority[it.sourceId] ?: Int.MAX_VALUE }
            ?: return null
        return ScheduleLibraryMatch(
            animeId = primary.animeId,
            sourceIds = found.items.mapTo(LinkedHashSet()) { it.sourceId },
            inLibrary = true,
            isManual = false,
        )
    }

    companion object {
        val EMPTY = ScheduleLibraryResolver(emptyList())

        fun sourceUrlKey(sourceId: Long, url: String) = "$sourceId|$url"
    }
}
