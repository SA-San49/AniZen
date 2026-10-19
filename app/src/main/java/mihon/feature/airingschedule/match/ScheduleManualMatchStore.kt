package mihon.feature.airingschedule.match

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.feature.airingschedule.SchedulePreferences
import tachiyomi.domain.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Persists the user's manual schedule picks ([ScheduleManualMatch]) in preferences, so they
 * survive restarts, library removal / re-adding, and switching between AniList and LiveChart.
 */
class ScheduleManualMatchStore(
    private val schedulePrefs: SchedulePreferences = Injekt.get(),
) {

    fun getAll(): List<ScheduleManualMatch> = decode(schedulePrefs.manualMatches().get())

    fun changes(): Flow<ScheduleManualMatchIndex> = schedulePrefs.manualMatches().changes()
        .distinctUntilChanged()
        .map { ScheduleManualMatchIndex(decode(it)) }

    fun index(): ScheduleManualMatchIndex = ScheduleManualMatchIndex(getAll())

    /** Save [anime] as the user's pick for the schedule entry described by [request]. */
    @Synchronized
    fun bind(request: ScheduleBindingRequest, anime: Anime) {
        val match = ScheduleManualMatch(
            sourceId = anime.source,
            url = anime.url,
            animeId = anime.id,
            animeTitle = anime.title,
            updatedAt = System.currentTimeMillis(),
        )
        val updated = ScheduleManualMatchIndex.upsert(getAll(), request, match)
        schedulePrefs.manualMatches().set(json.encodeToString(updated))
    }

    /** Bind from a serialized [ScheduleBindingRequest] (screen argument). Returns false if it is invalid. */
    fun bind(encodedRequest: String, anime: Anime): Boolean {
        val request = decodeRequest(encodedRequest) ?: return false
        bind(request, anime)
        return true
    }

    /** Remember a new database id for a pick whose anime row was re-created. */
    @Synchronized
    fun updateAnimeId(match: ScheduleManualMatch, newAnimeId: Long) {
        if (match.animeId == newAnimeId) return
        val updated = getAll().map {
            if (it.sourceId == match.sourceId && it.url == match.url) it.copy(animeId = newAnimeId) else it
        }
        schedulePrefs.manualMatches().set(json.encodeToString(updated))
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(raw: String): List<ScheduleManualMatch> {
            if (raw.isBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<ScheduleManualMatch>>(raw) }.getOrDefault(emptyList())
        }

        fun encodeRequest(request: ScheduleBindingRequest): String = json.encodeToString(request)

        fun decodeRequest(raw: String): ScheduleBindingRequest? =
            runCatching { json.decodeFromString<ScheduleBindingRequest>(raw) }.getOrNull()
    }
}
