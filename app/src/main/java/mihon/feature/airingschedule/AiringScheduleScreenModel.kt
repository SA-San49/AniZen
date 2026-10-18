package mihon.feature.airingschedule

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.feature.airingschedule.components.BellNotifyState
import eu.kanade.tachiyomi.util.system.toast
import mihon.feature.airingschedule.notification.AiringAlertSyncWorker
import mihon.feature.airingschedule.notification.AniListNextEpisodeLookup
import mihon.feature.airingschedule.notification.ScheduleNotifications
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

class AiringScheduleScreenModel(
    private val repository: AiringScheduleRepository = AiringScheduleRepository(),
    private val schedulePrefs: SchedulePreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val uploadDelayTracker: UploadDelayTracker = Injekt.get(),
    private val application: Application = Injekt.get(),
    private val getLibraryAnime: GetLibraryAnime = Injekt.get(),
) : StateScreenModel<AiringScheduleScreenModel.State>(State()) {

    private var allEntries: List<AiringScheduleEntry> = emptyList()
    private var hasLoaded = false
    private var lastTitleLanguage = schedulePrefs.titleLanguage().get()
    private var lastPrimarySource = schedulePrefs.primarySource().get()

    init {
        loadSchedule()
        observePreferences()
        observeLibrary()
    }

    private fun observeLibrary() {
        screenModelScope.launch {
            getLibraryAnime.subscribe().collectLatest { libraryAnime ->
                val (titles, sourcesByTitle, idByTitle) = withIOContext {
                    val titles = mutableSetOf<String>()
                    val sourcesByTitle = mutableMapOf<String, Set<String>>()
                    val idByTitle = mutableMapOf<String, Long>()

                    for (lib in libraryAnime) {
                        // Match both the user's custom/display title and the original source
                        // title. A custom title alone can hide a library item whose schedule
                        // entry uses the source's English or Romaji title.
                        val libraryTitles = listOf(lib.anime.title, lib.anime.ogTitle).distinct()
                        val keys = libraryTitles
                            .flatMap { mihon.feature.airingschedule.util.ScheduleTitleMatcher.normalizedKeys(it) }
                            .toSet()
                        titles.addAll(keys)
                        val sourceStr = lib.anime.source.toString()
                        for (k in keys) {
                            val existingSources = sourcesByTitle[k].orEmpty()
                            sourcesByTitle[k] = existingSources + sourceStr
                            idByTitle[k] = lib.anime.id
                        }
                    }
                    Triple(titles, sourcesByTitle, idByTitle)
                }

                mutableState.update {
                    it.copy(
                        libraryAnimeTitles = titles,
                        librarySourcesByTitle = sourcesByTitle,
                        libraryAnimeIdByTitle = idByTitle,
                    )
                }
                if (allEntries.isNotEmpty()) {
                    applyFilters()
                }
            }
        }
    }

    private fun observePreferences() {
        screenModelScope.launch {
            combine(
                schedulePrefs.showOnlyFavoriteSources().changes(),
                schedulePrefs.favoriteSourceIds().changes(),
                schedulePrefs.showAdultContent().changes(),
                schedulePrefs.titleLanguage().changes(),
                schedulePrefs.primarySource().changes(),
                schedulePrefs.uploadDelayRefreshInterval().changes(),
                schedulePrefs.customUploadDelayMinutes().changes(),
                schedulePrefs.sourceUploadDelays().changes(),
                schedulePrefs.viewMode().changes(),
                schedulePrefs.uploadDelayEnabled().changes(),
                // Bell state can change in the background (alert fired, series finished).
                schedulePrefs.notifyOnceMediaIds().changes(),
                schedulePrefs.notifySeriesMediaIds().changes(),
            ) { _ -> Unit }.collectLatest {
                // Upload-delay settings move the countdown, so move armed alerts with it.
                withIOContext { runCatching { ScheduleNotifications.rearmAll(application) } }
                val titleLanguage = schedulePrefs.titleLanguage().get()
                val primarySource = schedulePrefs.primarySource().get()
                if (titleLanguage != lastTitleLanguage || primarySource != lastPrimarySource) {
                    // Re-fetch after a language change so an older cached payload cannot keep
                    // displaying AniList's userPreferred/Romaji title.
                    lastTitleLanguage = titleLanguage
                    lastPrimarySource = primarySource
                    loadSchedule(forceRefresh = true)
                } else if (allEntries.isNotEmpty()) {
                    applyFilters()
                }
            }
        }
    }

    fun loadSchedule(forceRefresh: Boolean = false) {
        screenModelScope.launch {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay(zone)
            // Keep the API range exclusive at the next Monday boundary. The visible label still
            // ends on Sunday, so no airing is lost or assigned to the wrong week.
            val weekEndExclusive = weekStart.plusDays(7)
            val visibleWeekEnd = weekEndExclusive.minusDays(1).toLocalDate()
            val currentWeekStart = weekStart.toEpochSecond()

            // 1. Try reading disk cache first (Instant Offline Display, no blank screen)
            val requestedTitleLanguage = schedulePrefs.titleLanguage().get()
            val requestedPrimarySource = schedulePrefs.primarySource().get()
            val cache = ScheduleDataRefreshWorker.readCache(application)
                ?.takeIf {
                    it.titleLanguage == requestedTitleLanguage.name &&
                        it.primarySource == requestedPrimarySource.name
                }
            val cachedEntries = if (cache != null && cache.entries.isNotEmpty()) {
                cache.entries
            } else null

            val isCacheForCurrentWeek = cache != null && cache.weekStartEpoch == currentWeekStart

            if (cachedEntries != null && !forceRefresh) {
                allEntries = cachedEntries
                hasLoaded = true
                applyFilters(
                    entries = allEntries,
                    delays = if (schedulePrefs.uploadDelayEnabled().get()) uploadDelayTracker.getDelays() else emptyMap(),
                    weekStart = weekStart.toLocalDate(),
                    weekEnd = visibleWeekEnd,
                )
                // If cache is for the current week and was fetched within the last 12 hours, skip network fetch
                val cacheAge = System.currentTimeMillis() - (cache?.fetchedAt ?: 0L)
                if (isCacheForCurrentWeek && cacheAge < TimeUnit.HOURS.toMillis(12)) {
                    rescheduleSeriesAlarms()
                    return@launch
                }
            }

            // 2. Fetch live data from AniList
            if (allEntries.isEmpty()) {
                mutableState.update { it.copy(isLoading = true, error = null) }
            }

            try {
                val includeAdult = schedulePrefs.showAdultContent().get()
                val fetched = repository.getWeeklySchedule(
                    weekStart.toEpochSecond(),
                    weekEndExclusive.toEpochSecond(),
                    includeAdult = includeAdult,
                    titleLanguage = requestedTitleLanguage,
                    primarySource = requestedPrimarySource,
                )

                // Persist live fetch to disk cache
                ScheduleDataRefreshWorker.writeCache(
                    context = application,
                    weekStartEpoch = currentWeekStart,
                    entries = fetched,
                    titleLanguage = requestedTitleLanguage,
                    primarySource = requestedPrimarySource,
                )

                allEntries = fetched
                hasLoaded = true

                val delays = if (schedulePrefs.uploadDelayEnabled().get()) {
                    uploadDelayTracker.getDelays()
                } else {
                    emptyMap()
                }

                rescheduleSeriesAlarms()

                applyFilters(
                    entries = allEntries,
                    delays = delays,
                    weekStart = weekStart.toLocalDate(),
                    weekEnd = visibleWeekEnd,
                )
            } catch (e: Exception) {
                if (allEntries.isEmpty()) {
                    val fallback = cache?.takeIf { it.weekStartEpoch == currentWeekStart }
                        ?: cache?.takeIf { it.entries.isNotEmpty() }
                    if (fallback != null) {
                        allEntries = fallback.entries
                        hasLoaded = true
                        applyFilters(
                            entries = allEntries,
                            delays = if (schedulePrefs.uploadDelayEnabled().get()) uploadDelayTracker.getDelays() else emptyMap(),
                            weekStart = weekStart.toLocalDate(),
                            weekEnd = visibleWeekEnd,
                        )
                    } else {
                        mutableState.update { it.copy(isLoading = false, error = e.message) }
                    }
                } else {
                    mutableState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /**
     * Manual override: when the user has picked "Custom" for the upload-delay refresh interval,
     * they've supplied a fixed delay themselves — that takes priority over any auto-learned
     * per-source delay when computing expected upload time / countdown.
     */
    private fun computeManualDelayMinutes(): Long? {
        if (!schedulePrefs.uploadDelayEnabled().get()) return null
        if (schedulePrefs.uploadDelayRefreshInterval().get() != SchedulePreferences.UploadDelayInterval.CUSTOM) {
            return null
        }
        return SchedulePreferences.parseCustomDelayMinutes(schedulePrefs.customUploadDelayMinutes().get())
    }

    /**
     * The actual favourite/pinned source ids that carry *this specific* anime, resolved via the
     * library-anime title match (bounded to anime the user already added — we can't check every
     * source's full catalogue for every scheduled anime without being far too slow, so this only
     * reports "confirmed on a favourite source" for library anime).
     */
    private fun matchedSourcesFor(
        entry: AiringScheduleEntry,
        configuredSources: Set<String>,
        librarySourcesByTitle: Map<String, Set<String>>,
    ): Set<String> {
        val titleCandidates = mihon.feature.airingschedule.util.ScheduleTitleMatcher.candidateTitlesFromEntry(entry)
        val candidateKeys = titleCandidates.flatMap { mihon.feature.airingschedule.util.ScheduleTitleMatcher.normalizedKeys(it) }
        val candidateSources = candidateKeys.flatMap { librarySourcesByTitle[it].orEmpty() }.toSet()
        return candidateSources.intersect(configuredSources)
    }

    /**
     * Resolves the learned upload delay according to pinned sources priority order,
     * falling back to favorite sources or the largest learned delay.
     */
    private fun priorityDelayFor(
        matchedSources: Set<String>,
        manualDelayMinutes: Long?,
        delays: Map<String, Long>,
        pinnedSources: Set<String>,
        favoriteIds: Set<String>,
    ): Long? {
        manualDelayMinutes?.let { return it }
        if (delays.isEmpty()) return null

        // 1. If this anime is matched to specific sources in library, check pinned then favorite in order
        if (matchedSources.isNotEmpty()) {
            for (pinned in pinnedSources) {
                if (pinned in matchedSources && delays.containsKey(pinned)) {
                    return delays[pinned]
                }
            }
            for (fav in favoriteIds) {
                if (fav in matchedSources && delays.containsKey(fav)) {
                    return delays[fav]
                }
            }
            val firstMatched = matchedSources.firstNotNullOfOrNull { delays[it] }
            if (firstMatched != null) return firstMatched
        }

        // 2. Otherwise check pinned sources in priority order
        for (pinned in pinnedSources) {
            if (delays.containsKey(pinned)) {
                return delays[pinned]
            }
        }

        // 3. Fallback to favorite IDs
        for (fav in favoriteIds) {
            if (delays.containsKey(fav)) {
                return delays[fav]
            }
        }

        return delays.values.maxOrNull()
    }

    private fun isEntryInLibrary(
        entry: AiringScheduleEntry,
        libraryAnimeTitles: Set<String>,
        libraryAnimeIdByTitle: Map<String, Long>,
    ): Boolean {
        val titleCandidates = mihon.feature.airingschedule.util.ScheduleTitleMatcher.candidateTitlesFromEntry(entry)
        val candidateKeys = titleCandidates.flatMap { mihon.feature.airingschedule.util.ScheduleTitleMatcher.normalizedKeys(it) }
        return candidateKeys.any { it in libraryAnimeTitles || libraryAnimeIdByTitle.containsKey(it) }
    }

    private fun filterEntries(
        entries: List<AiringScheduleEntry>,
        showAdult: Boolean,
        showOnlyFavorites: Boolean,
        hideAired: Boolean,
        selectedFormats: Set<String>,
        configuredSources: Set<String>,
        libraryAnimeTitles: Set<String>,
        librarySourcesByTitle: Map<String, Set<String>>,
        libraryAnimeIdByTitle: Map<String, Long>,
    ): List<AiringScheduleEntry> = entries.filter { entry ->
        // Re-apply adult-content filter in case the preference changed since last fetch.
        if (!showAdult && entry.isAdult) return@filter false
        if (hideAired && entry.hasAired()) return@filter false
        if (selectedFormats.isNotEmpty() && (entry.format == null || entry.format !in selectedFormats)) return@filter false

        // When showOnlyFavorites is true:
        // Filter out everything except anime in the user's library.
        if (showOnlyFavorites) {
            val inLibrary = isEntryInLibrary(entry, libraryAnimeTitles, libraryAnimeIdByTitle)
            if (!inLibrary) return@filter false

            // If specific favorite/pinned sources are selected in settings, filter out
            // everything except those that are from those sources added to the library.
            if (configuredSources.isNotEmpty()) {
                val matchedSources = matchedSourcesFor(entry, configuredSources, librarySourcesByTitle)
                if (matchedSources.isEmpty()) return@filter false
            }
        }
        true
    }

    private fun groupByDelayAdjustedDay(
        entries: List<AiringScheduleEntry>,
        configuredSources: Set<String>,
        librarySourcesByTitle: Map<String, Set<String>>,
        manualDelayMinutes: Long?,
        delays: Map<String, Long>,
        pinnedSources: Set<String>,
        favoriteIds: Set<String>,
        zone: ZoneId,
    ): Map<DayOfWeek, List<AiringScheduleEntry>> = entries.groupBy { entry ->
        val matchedSources = if (configuredSources.isNotEmpty()) {
            matchedSourcesFor(entry, configuredSources, librarySourcesByTitle)
        } else {
            emptySet()
        }
        val priorityDelay = priorityDelayFor(matchedSources, manualDelayMinutes, delays, pinnedSources, favoriteIds)
        val airTime = if (priorityDelay != null) entry.airingAt + (priorityDelay * 60) else entry.airingAt
        ZonedDateTime.ofInstant(Instant.ofEpochSecond(airTime), zone).dayOfWeek
    }

    private fun applyFilters(
        entries: List<AiringScheduleEntry> = allEntries,
        delays: Map<String, Long> = if (schedulePrefs.uploadDelayEnabled().get()) uploadDelayTracker.getDelays() else emptyMap(),
        weekStart: LocalDate? = mutableState.value.weekStartDate,
        weekEnd: LocalDate? = mutableState.value.weekEndDate,
    ) {
        val showOnlyFavorites = schedulePrefs.showOnlyFavoriteSources().get()
        val favoriteIds = schedulePrefs.favoriteSourceIds().get()
        val showAdult = schedulePrefs.showAdultContent().get()
        val titleLang = schedulePrefs.titleLanguage().get()
        val pinnedSources = sourcePreferences.pinnedSources().get()
        val librarySourcesByTitle = mutableState.value.librarySourcesByTitle
        val libraryAnimeTitles = mutableState.value.libraryAnimeTitles
        val libraryAnimeIdByTitle = mutableState.value.libraryAnimeIdByTitle
        val hideAired = mutableState.value.hideAired
        val selectedFormats = mutableState.value.selectedFormats
        val manualDelayMinutes = computeManualDelayMinutes()
        // Source filters should apply for either favourite or pinned sources — a user who
        // only pins sources from Browse (without also marking them "favourite" here) still
        // expects "show only my sources" to work.
        val configuredSources = favoriteIds + pinnedSources

        val filtered = filterEntries(
            entries = entries,
            showAdult = showAdult,
            showOnlyFavorites = showOnlyFavorites,
            hideAired = hideAired,
            selectedFormats = selectedFormats,
            configuredSources = configuredSources,
            libraryAnimeTitles = libraryAnimeTitles,
            librarySourcesByTitle = librarySourcesByTitle,
            libraryAnimeIdByTitle = libraryAnimeIdByTitle,
        )
        val grouped = groupByDelayAdjustedDay(
            entries = filtered,
            configuredSources = configuredSources,
            librarySourcesByTitle = librarySourcesByTitle,
            manualDelayMinutes = manualDelayMinutes,
            delays = delays,
            pinnedSources = pinnedSources,
            favoriteIds = favoriteIds,
            zone = ZoneId.systemDefault(),
        )

        mutableState.update {
            it.copy(
                isLoading = false,
                scheduleByDay = grouped,
                allFilteredEntries = filtered,
                viewMode = schedulePrefs.viewMode().get(),
                weekStartDate = weekStart,
                weekEndDate = weekEnd,
                titleLanguage = titleLang,
                sourceDelays = delays,
                manualDelayMinutes = manualDelayMinutes,
                favoriteSourceIds = favoriteIds,
                pinnedSourceIds = pinnedSources,
                onlyFavorites = showOnlyFavorites,
                showAdult = showAdult,
                notifyOnceMediaIds = schedulePrefs.notifyOnceMediaIds().get(),
                notifySeriesMediaIds = schedulePrefs.notifySeriesMediaIds().get(),
            )
        }
    }

    fun setFilterOnlyFavorites(value: Boolean) {
        schedulePrefs.showOnlyFavoriteSources().set(value)
    }

    fun setFilterHideAired(value: Boolean) {
        mutableState.update { it.copy(hideAired = value) }
        applyFilters()
    }

    fun setFilterShowAdult(value: Boolean) {
        schedulePrefs.showAdultContent().set(value)
    }

    fun toggleFilterFormat(format: String) {
        mutableState.update {
            val next = if (format in it.selectedFormats) it.selectedFormats - format else it.selectedFormats + format
            it.copy(selectedFormats = next)
        }
        applyFilters()
    }

    fun resetFilters() {
        schedulePrefs.showOnlyFavoriteSources().set(false)
        schedulePrefs.showAdultContent().set(false)
        mutableState.update {
            it.copy(
                onlyFavorites = false,
                hideAired = false,
                showAdult = false,
                selectedFormats = emptySet(),
            )
        }
        applyFilters()
    }

    /** Determines the current bell state for a given schedule entry. */
    fun notifyStateFor(mediaId: Int): BellNotifyState {
        val state = mutableState.value
        return when {
            mediaId.toString() in state.notifySeriesMediaIds -> BellNotifyState.SERIES
            mediaId.toString() in state.notifyOnceMediaIds -> BellNotifyState.ONCE
            else -> BellNotifyState.NONE
        }
    }

    /**
     * Single tap on the bell.
     * - Off  -> alert once, when this episode's local countdown reaches zero (or the next upcoming
     *           episode if this one already aired).
     * - Once -> turn the alert off.
     * - Every episode -> step down to a single alert for the next episode.
     */
    fun toggleNotifyOnce(entry: AiringScheduleEntry) {
        val mediaId = entry.mediaId
        val title = entry.displayTitle(schedulePrefs.titleLanguage().get())
        if (ScheduleNotifications.isOnce(mediaId) && !ScheduleNotifications.isSeries(mediaId)) {
            ScheduleNotifications.setOnce(mediaId, false)
            ScheduleNotifications.cancelAllForMedia(application, mediaId)
            application.toast("Alert cancelled for $title")
            applyFilters()
            return
        }

        val wasSeries = ScheduleNotifications.isSeries(mediaId)
        screenModelScope.launch {
            val target = resolveNextAlertTarget(entry)
            if (wasSeries) {
                ScheduleNotifications.setSeries(mediaId, false)
                if (ScheduleNotifications.seriesMediaIds().isEmpty()) {
                    AiringAlertSyncWorker.cancelAll(application)
                }
            }
            ScheduleNotifications.cancelAllForMedia(application, mediaId)
            if (target != null && ScheduleNotifications.ensureScheduled(application, target)) {
                ScheduleNotifications.setOnce(mediaId, true)
                application.toast(
                    if (wasSeries) {
                        "Stopped alerts for every episode. You'll still be notified for episode ${target.episode}"
                    } else {
                        "You'll be notified when episode ${target.episode} of $title airs"
                    },
                )
            } else {
                ScheduleNotifications.setOnce(mediaId, false)
                application.toast(
                    if (wasSeries) "Alerts turned off for $title" else "No upcoming episode of $title to notify about",
                )
            }
            applyFilters()
        }
    }

    /**
     * Long press on the bell. Toggles alerts for every new episode until the series finishes
     * airing or the user turns it off. Survives weekly schedule refreshes because the next episode
     * is looked up on AniList directly by [AiringAlertSyncWorker] (after every alert, every 12h,
     * on app start and after reboot), not taken from the currently loaded week.
     */
    fun toggleNotifySeries(entry: AiringScheduleEntry) {
        val mediaId = entry.mediaId
        val title = entry.displayTitle(schedulePrefs.titleLanguage().get())
        if (ScheduleNotifications.isSeries(mediaId)) {
            ScheduleNotifications.endSeries(application, mediaId)
            ScheduleNotifications.setOnce(mediaId, false)
            application.toast("Stopped alerts for every episode of $title")
            applyFilters()
            return
        }

        ScheduleNotifications.setOnce(mediaId, false)
        ScheduleNotifications.setSeries(mediaId, true)
        // Arm what is already known from the loaded schedule right away (keeps any pending alert
        // from a previous single tap), then let the sync find episodes beyond this week.
        val delay = ScheduleNotifications.currentDelayMinutes()
        allEntries
            .filter { it.mediaId == mediaId && ScheduleNotifications.isUpcoming(it, delay) }
            .forEach { ScheduleNotifications.ensureScheduled(application, it) }
        AiringAlertSyncWorker.ensurePeriodic(application)
        AiringAlertSyncWorker.enqueueNow(application)
        application.toast("You'll be notified for every new episode of $title until it finishes airing")
        applyFilters()
    }

    /**
     * The episode a single-tap alert should target: the tapped one if its countdown is still
     * running, otherwise the next upcoming episode from the loaded schedule, otherwise AniList's
     * next airing episode for the show.
     */
    private suspend fun resolveNextAlertTarget(entry: AiringScheduleEntry): AiringScheduleEntry? {
        val delay = ScheduleNotifications.currentDelayMinutes()
        if (ScheduleNotifications.isUpcoming(entry, delay)) return entry
        allEntries
            .filter { it.mediaId == entry.mediaId && ScheduleNotifications.isUpcoming(it, delay) }
            .minByOrNull { it.airingAt }
            ?.let { return it }
        if (entry.mediaId <= 0) return null
        return withIOContext {
            runCatching {
                AniListNextEpisodeLookup().fetch(listOf(entry.mediaId))[entry.mediaId]?.nextEpisode
            }.getOrNull()
        }?.takeIf { ScheduleNotifications.isUpcoming(it, delay) }
    }

    private fun rescheduleSeriesAlarms() {
        val seriesIds = ScheduleNotifications.seriesMediaIds()
        if (seriesIds.isEmpty()) return
        val delay = ScheduleNotifications.currentDelayMinutes()
        allEntries
            .filter { it.mediaId in seriesIds && ScheduleNotifications.isUpcoming(it, delay) }
            .forEach { ScheduleNotifications.ensureScheduled(application, it) }
    }

    fun selectDay(day: DayOfWeek) {
        mutableState.update { it.copy(selectedDay = day) }
    }

    fun clearLearnedDelays() {
        uploadDelayTracker.clearAllDelays()
        applyFilters(delays = emptyMap())
    }

    fun toggleViewMode() {
        val current = schedulePrefs.viewMode().get()
        val next = if (current == SchedulePreferences.ViewMode.WEEKLY) {
            SchedulePreferences.ViewMode.MONTHLY
        } else {
            SchedulePreferences.ViewMode.WEEKLY
        }
        schedulePrefs.viewMode().set(next)
        mutableState.update { it.copy(viewMode = next) }
    }

    fun setViewMode(mode: SchedulePreferences.ViewMode) {
        schedulePrefs.viewMode().set(mode)
        mutableState.update { it.copy(viewMode = mode) }
    }

    data class State(
        val isLoading: Boolean = true,
        val scheduleByDay: Map<DayOfWeek, List<AiringScheduleEntry>> = emptyMap(),
        val allFilteredEntries: List<AiringScheduleEntry> = emptyList(),
        val viewMode: SchedulePreferences.ViewMode = SchedulePreferences.ViewMode.WEEKLY,
        val selectedDay: DayOfWeek = ZonedDateTime.now().dayOfWeek,
        val weekStartDate: LocalDate? = null,
        val weekEndDate: LocalDate? = null,
        val error: String? = null,
        val titleLanguage: SchedulePreferences.TitleLanguage = SchedulePreferences.TitleLanguage.USER_PREFERRED,
        val sourceDelays: Map<String, Long> = emptyMap(),
        val manualDelayMinutes: Long? = null,
        val favoriteSourceIds: Set<String> = emptySet(),
        val pinnedSourceIds: Set<String> = emptySet(),
        val notifyOnceMediaIds: Set<String> = emptySet(),
        val notifySeriesMediaIds: Set<String> = emptySet(),
        val libraryAnimeTitles: Set<String> = emptySet(),
        val librarySourcesByTitle: Map<String, Set<String>> = emptyMap(),
        val libraryAnimeIdByTitle: Map<String, Long> = emptyMap(),
        val onlyFavorites: Boolean = false,
        val hideAired: Boolean = false,
        val showAdult: Boolean = false,
        val selectedFormats: Set<String> = emptySet(),
    ) {
        val hasActiveFilters: Boolean
            get() = onlyFavorites || hideAired || showAdult || selectedFormats.isNotEmpty()
    }
}
