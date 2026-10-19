package mihon.feature.airingschedule

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.feature.airingschedule.util.ScheduleTitleMatcher
import tachiyomi.domain.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * An anime the user explicitly chose (from global search / a source opened from it) for a
 * schedule entry. A manual pick has absolute priority over every automatic library title
 * match.
 *
 * The pick is identified by [sourceId] + [url] — the same identity the anime database uses —
 * rather than by the local anime id, so it keeps working after the anime is removed from and
 * re-added to the library (or its row is recreated by a database cleanup).
 */
@Serializable
data class ScheduleManualPick(
    val sourceId: Long,
    val url: String,
    val title: String = "",
    /** Local anime id at pick time; only a hint, [sourceId] + [url] is authoritative. */
    val animeId: Long = -1L,
    val thumbnailUrl: String? = null,
    /** Normalized main titles of the schedule entry, used when its media id changes (e.g. AniList ↔ LiveChart). */
    val titleKeys: Set<String> = emptySet(),
    val pickedAt: Long = 0L,
) {
    val identity: String get() = ScheduleManualPicks.identityOf(sourceId, url)
}

/**
 * Passed from the schedule to global search (and a source opened from it) so that the anime
 * the user taps is remembered as the manual pick for that schedule entry.
 *
 * Kept java-[java.io.Serializable] with plain fields because it is a constructor argument of
 * Voyager screens, which are saved with the navigator state.
 */
data class SchedulePickRequest(
    val mediaId: Int,
    val titleKeys: List<String>,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L

        fun from(entry: AiringScheduleEntry) = SchedulePickRequest(
            mediaId = entry.mediaId,
            titleKeys = ArrayList(ScheduleManualPicks.mainTitleKeys(entry)),
        )
    }
}

/** Library state of a picked anime, resolved from the database. */
data class ScheduleManualPickRow(
    val animeId: Long,
    val inLibrary: Boolean,
)

/** The library anime a schedule entry resolves to. */
data class ScheduleEntryMatch(
    /** Anime to open on tap, or null to start a global search / insert the pick. */
    val animeId: Long?,
    val inLibrary: Boolean,
    /** Source ids (as strings) the entry is matched to — used for upload-delay priority/filters. */
    val sources: Set<String>,
    /** Non-null when the user's manual pick decided this match. */
    val manualPick: ScheduleManualPick?,
) {
    companion object {
        val NONE = ScheduleEntryMatch(animeId = null, inLibrary = false, sources = emptySet(), manualPick = null)
    }
}

/**
 * Everything needed to resolve a schedule entry to library anime. Resolution order:
 * 1. The user's manual pick (always wins, even when it is not in the library).
 * 2. Automatic title matching against library titles.
 */
data class ScheduleLibraryIndex(
    val titles: Set<String> = emptySet(),
    val sourcesByTitle: Map<String, Set<String>> = emptyMap(),
    val idByTitle: Map<String, Long> = emptyMap(),
    val manualPicks: Map<String, ScheduleManualPick> = emptyMap(),
    /** Keyed by [ScheduleManualPick.identity]. */
    val pickRows: Map<String, ScheduleManualPickRow> = emptyMap(),
) {
    fun resolve(entry: AiringScheduleEntry): ScheduleEntryMatch {
        val pick = ScheduleManualPicks.find(manualPicks, entry)
        if (pick != null) {
            val row = pickRows[pick.identity]
            return ScheduleEntryMatch(
                animeId = row?.animeId,
                inLibrary = row?.inLibrary == true,
                sources = setOf(pick.sourceId.toString()),
                manualPick = pick,
            )
        }
        if (titles.isEmpty() && idByTitle.isEmpty()) return ScheduleEntryMatch.NONE
        val keys = ScheduleTitleMatcher.candidateTitlesFromEntry(entry)
            .flatMap { ScheduleTitleMatcher.normalizedKeys(it) }
        val animeId = keys.firstNotNullOfOrNull { idByTitle[it] }
        val inLibrary = animeId != null || keys.any { it in titles }
        if (!inLibrary) return ScheduleEntryMatch.NONE
        return ScheduleEntryMatch(
            animeId = animeId,
            inLibrary = true,
            sources = keys.flatMapTo(mutableSetOf()) { sourcesByTitle[it].orEmpty() },
            manualPick = null,
        )
    }
}

object ScheduleManualPicks {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun identityOf(sourceId: Long, url: String): String = "$sourceId|$url"

    fun keyFor(mediaId: Int): String = mediaId.toString()

    fun decode(raw: String?): Map<String, ScheduleManualPick> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, ScheduleManualPick>>(raw) }
            .getOrDefault(emptyMap())
    }

    fun encode(picks: Map<String, ScheduleManualPick>): String = json.encodeToString(picks)

    /**
     * Normalized keys of the entry's main titles (no aliases — aliases are too generic to
     * safely carry a pick over to another entry).
     */
    fun mainTitleKeys(entry: AiringScheduleEntry): Set<String> = listOfNotNull(
        entry.titleUserPreferred,
        entry.titleEnglish,
        entry.titleRomaji,
        entry.titleNative,
    )
        .filter { it.isNotBlank() }
        .flatMapTo(mutableSetOf()) { ScheduleTitleMatcher.normalizedKeys(it) }

    /**
     * The manual pick for [entry]: by media id first; otherwise the newest pick recorded for an
     * entry with the same main title (covers switching the schedule source, whose ids differ).
     */
    fun find(picks: Map<String, ScheduleManualPick>, entry: AiringScheduleEntry): ScheduleManualPick? {
        if (picks.isEmpty()) return null
        picks[keyFor(entry.mediaId)]?.let { return it }
        val keys = mainTitleKeys(entry)
        if (keys.isEmpty()) return null
        return picks.values
            .filter { pick -> pick.titleKeys.any { it in keys } }
            .maxByOrNull { it.pickedAt }
    }

    /** Returns [picks] with [pick] stored as the manual choice for [request]'s schedule entry. */
    fun withPick(
        picks: Map<String, ScheduleManualPick>,
        request: SchedulePickRequest,
        pick: ScheduleManualPick,
    ): Map<String, ScheduleManualPick> =
        picks + (keyFor(request.mediaId) to pick.copy(titleKeys = request.titleKeys.toSet()))

    /** Persists the anime the user chose for [request]'s schedule entry. */
    fun record(
        prefs: SchedulePreferences,
        request: SchedulePickRequest,
        sourceId: Long,
        url: String,
        title: String,
        animeId: Long,
        thumbnailUrl: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        if (url.isBlank()) return
        val pref = prefs.manualAnimePicks()
        val updated = withPick(
            picks = decode(pref.get()),
            request = request,
            pick = ScheduleManualPick(
                sourceId = sourceId,
                url = url,
                title = title,
                animeId = animeId,
                thumbnailUrl = thumbnailUrl,
                pickedAt = now,
            ),
        )
        pref.set(encode(updated))
    }

    /** Forgets the manual pick for [mediaId] (automatic matching applies again). */
    fun clear(prefs: SchedulePreferences, mediaId: Int) {
        val pref = prefs.manualAnimePicks()
        val current = decode(pref.get())
        val key = keyFor(mediaId)
        if (key in current) pref.set(encode(current - key))
    }
}

/** Records [anime] (tapped in a schedule-started search) as the manual pick for [request]'s entry. */
fun recordSchedulePick(request: SchedulePickRequest, anime: Anime) {
    runCatching {
        ScheduleManualPicks.record(
            prefs = Injekt.get<SchedulePreferences>(),
            request = request,
            sourceId = anime.source,
            url = anime.url,
            title = anime.ogTitle.ifBlank { anime.title },
            animeId = anime.id,
            thumbnailUrl = anime.ogThumbnailUrl,
        )
    }
}
