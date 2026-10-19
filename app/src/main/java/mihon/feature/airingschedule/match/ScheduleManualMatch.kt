package mihon.feature.airingschedule.match

import kotlinx.serialization.Serializable
import mihon.feature.airingschedule.AiringScheduleEntry
import mihon.feature.airingschedule.util.ScheduleDeepTitleMatcher
import mihon.feature.airingschedule.util.ScheduleTitleMatcher

/**
 * The user's own choice of which source anime a schedule entry is.
 *
 * Picked by tapping a result in Global Search (or inside a source opened from it) after the
 * schedule launched that search. It has the highest priority: it overrides every automatic
 * library title match. The anime is identified by `sourceId + url`, so the choice survives
 * removing the anime from the library and adding it back (even if its database id changes).
 *
 * It is keyed both by the schedule provider id (`anilist:<id>` / `livechart:<id>`) and by
 * deep title keys, so the same choice is found whether AniList or LiveChart is the primary
 * schedule source.
 */
@Serializable
data class ScheduleManualMatch(
    val sourceId: Long,
    val url: String,
    val animeId: Long,
    val animeTitle: String,
    val mediaKeys: List<String> = emptyList(),
    val titleKeys: List<String> = emptyList(),
    val updatedAt: Long = 0L,
)

/** What a schedule-launched Global Search binds the user's pick to. Serialized into the screen args. */
@Serializable
data class ScheduleBindingRequest(
    val mediaKey: String,
    val titleKeys: List<String>,
    val query: String,
)

object ScheduleMatchKeys {

    fun mediaKey(mediaId: Int): String =
        if (mediaId >= 0) "anilist:$mediaId" else "livechart:${-mediaId}"

    fun mediaKey(entry: AiringScheduleEntry): String = mediaKey(entry.mediaId)

    fun titleKeys(titles: Collection<String?>): List<String> = titles.asSequence()
        .filterNotNull()
        .filter { it.isNotBlank() }
        .map { ScheduleDeepTitleMatcher.parse(it) }
        .filterNot { it.isEmpty }
        .map { it.key }
        .distinct()
        .toList()

    fun titleKeys(entry: AiringScheduleEntry): List<String> =
        titleKeys(ScheduleTitleMatcher.candidateTitlesFromEntry(entry))

    fun bindingRequest(entry: AiringScheduleEntry, query: String) = ScheduleBindingRequest(
        mediaKey = mediaKey(entry),
        titleKeys = titleKeys(entry),
        query = query,
    )
}

/** Immutable lookup over all saved manual matches. */
class ScheduleManualMatchIndex(val matches: List<ScheduleManualMatch>) {

    private val byMedia = HashMap<String, ScheduleManualMatch>()
    private val byTitle = HashMap<String, ScheduleManualMatch>()

    init {
        // Oldest first so the most recent choice wins any key it shares.
        for (match in matches.sortedBy { it.updatedAt }) {
            match.mediaKeys.forEach { byMedia[it] = match }
            match.titleKeys.forEach { byTitle[it] = match }
        }
    }

    val isEmpty: Boolean get() = matches.isEmpty()

    fun find(mediaKey: String, titleKeys: Collection<String>): ScheduleManualMatch? =
        byMedia[mediaKey] ?: titleKeys.firstNotNullOfOrNull { byTitle[it] }

    fun find(entry: AiringScheduleEntry): ScheduleManualMatch? {
        if (matches.isEmpty()) return null
        return find(ScheduleMatchKeys.mediaKey(entry), ScheduleMatchKeys.titleKeys(entry))
    }

    companion object {
        val EMPTY = ScheduleManualMatchIndex(emptyList())

        /**
         * Returns the match list with [newMatch] stored for [request], replacing any older
         * choice for the same schedule entry (same provider id or same title key).
         */
        fun upsert(
            current: List<ScheduleManualMatch>,
            request: ScheduleBindingRequest,
            newMatch: ScheduleManualMatch,
        ): List<ScheduleManualMatch> {
            val requestTitleKeys = request.titleKeys.toSet()
            val (replaced, kept) = current.partition { old ->
                request.mediaKey in old.mediaKeys || old.titleKeys.any { it in requestTitleKeys }
            }
            // Keep provider ids learned earlier (e.g. the LiveChart id of the same show).
            val mergedMediaKeys = (listOf(request.mediaKey) + replaced.flatMap { it.mediaKeys }).distinct()
            val mergedTitleKeys = (request.titleKeys + replaced.flatMap { it.titleKeys }).distinct()
            return kept + newMatch.copy(mediaKeys = mergedMediaKeys, titleKeys = mergedTitleKeys)
        }
    }
}
