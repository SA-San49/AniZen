package mihon.feature.announcements

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnnouncementsScreenModel(
    private val repository: AnnouncementsRepository = AnnouncementsRepository(),
    private val preferences: AnnouncementsPreferences = Injekt.get(),
) : StateScreenModel<AnnouncementsScreenModel.State>(
    repository.getCached()?.let { cached ->
        State.Success(
            allEntries = cached.toImmutableList(),
            selectedCategory = preferences.categoryFilter().get().takeIf { it.isNotEmpty() }?.let { runCatching { AnnouncementCategory.valueOf(it) }.getOrNull() },
            selectedYear = preferences.yearFilter().get().toIntOrNull(),
            sort = preferences.sort().get(),
            includeAdult = preferences.includeAdult().get(),
            lockBanner = preferences.lockBanner().get(),
            showCatalog = preferences.showCatalog().get(),
            autoRefresh = preferences.autoRefresh().get(),
            fromCache = true,
        )
    } ?: State.Loading,
) {
    var showFiltersDialog by mutableStateOf(false)
        private set

    /** True while a user-initiated refresh runs on top of already visible content. */
    var isRefreshing by mutableStateOf(false)
        private set

    fun openFilters() {
        showFiltersDialog = true
    }

    fun closeFilters() {
        showFiltersDialog = false
    }

    sealed interface State {
        data object Loading : State

        data class Success(
            val allEntries: ImmutableList<AnnouncementEntry>,
            val selectedCategory: AnnouncementCategory?,
            val selectedYear: Int?,
            val sort: AnnouncementSort,
            val includeAdult: Boolean,
            val lockBanner: Boolean,
            val showCatalog: Boolean,
            val autoRefresh: AnnouncementAutoRefresh,
            val fromCache: Boolean,
        ) : State {
            // Computed once per state instance (not on every access / recomposition).
            val filteredEntries: ImmutableList<AnnouncementEntry> by lazy {
                allEntries
                    .asSequence()
                    .filter { selectedCategory == null || it.category == selectedCategory }
                    .filter { selectedYear == null || it.expectedYear == selectedYear }
                    .filter { includeAdult || !it.isAdult }
                    .let { entries ->
                        when (sort) {
                            AnnouncementSort.AIRING_SOON -> entries.sortedWith(
                                compareBy<AnnouncementEntry> { it.exactReleaseDate ?: Long.MAX_VALUE }
                                    .thenBy { it.title.lowercase() },
                            )
                            AnnouncementSort.LATEST_ADDED -> entries.sortedWith(
                                compareByDescending<AnnouncementEntry> { it.mediaId }
                                    .thenBy { it.title.lowercase() },
                            )
                            AnnouncementSort.POPULARITY -> entries.sortedWith(
                                compareByDescending<AnnouncementEntry> { it.popularity ?: -1 }
                                    .thenBy { it.title.lowercase() },
                            )
                            AnnouncementSort.SCORE -> entries.sortedWith(
                                compareByDescending<AnnouncementEntry> { it.score ?: -1 }
                                    .thenBy { it.title.lowercase() },
                            )
                            AnnouncementSort.TITLE -> entries.sortedBy { it.title.lowercase() }
                        }
                    }
                    .toList()
                    .toImmutableList()
            }

            val availableYears: List<Int> by lazy {
                allEntries
                    .mapNotNull { it.expectedYear }
                    .distinct()
                    .sortedDescending()
            }

            /** Per-category counts honouring every filter except the category itself. */
            val categoryCounts: Map<AnnouncementCategory, Int> by lazy {
                allEntries
                    .asSequence()
                    .filter { selectedYear == null || it.expectedYear == selectedYear }
                    .filter { includeAdult || !it.isAdult }
                    .groupingBy { it.category }
                    .eachCount()
            }

            val hasContentFilters: Boolean
                get() = selectedCategory != null || selectedYear != null
        }

        data class Error(val cachedEntries: ImmutableList<AnnouncementEntry>) : State
    }

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        if (forceRefresh && isRefreshing) return
        screenModelScope.launch {
            val previous = mutableState.value as? State.Success
            if (previous == null) {
                mutableState.value = State.Loading
            } else if (forceRefresh) {
                // Keep the list on screen and show the pull-to-refresh indicator instead
                isRefreshing = true
            }
            try {
                applyResult(previous, repository.getAnnouncements(forceRefresh))
            } finally {
                isRefreshing = false
            }
        }
    }

    private fun applyResult(previous: State.Success?, result: AnnouncementsRepository.Result) {
        // Prefer the latest state so filter changes made during a refresh are not reverted
        val latest = mutableState.value as? State.Success ?: previous
        when (result) {
            is AnnouncementsRepository.Result.Success -> {
                mutableState.value = State.Success(
                    allEntries = result.entries.toImmutableList(),
                    selectedCategory = latest?.selectedCategory
                        ?: preferences.categoryFilter().get().takeIf { it.isNotEmpty() }?.let { runCatching { AnnouncementCategory.valueOf(it) }.getOrNull() },
                    selectedYear = latest?.selectedYear ?: preferences.yearFilter().get().toIntOrNull(),
                    sort = latest?.sort ?: preferences.sort().get(),
                    includeAdult = latest?.includeAdult ?: preferences.includeAdult().get(),
                    lockBanner = latest?.lockBanner ?: preferences.lockBanner().get(),
                    showCatalog = latest?.showCatalog ?: preferences.showCatalog().get(),
                    autoRefresh = latest?.autoRefresh ?: preferences.autoRefresh().get(),
                    fromCache = result.fromCache,
                )
            }
            is AnnouncementsRepository.Result.Failure -> {
                mutableState.value = latest ?: State.Error(result.cachedEntries.toImmutableList())
            }
        }
    }

    fun showCachedData() {
        val cached = (mutableState.value as? State.Error)?.cachedEntries ?: return
        mutableState.value = State.Success(
            allEntries = cached,
            selectedCategory = preferences.categoryFilter().get().takeIf { it.isNotEmpty() }?.let { runCatching { AnnouncementCategory.valueOf(it) }.getOrNull() },
            selectedYear = preferences.yearFilter().get().toIntOrNull(),
            sort = preferences.sort().get(),
            includeAdult = preferences.includeAdult().get(),
            lockBanner = preferences.lockBanner().get(),
            showCatalog = preferences.showCatalog().get(),
            autoRefresh = preferences.autoRefresh().get(),
            fromCache = true,
        )
    }

    fun selectCategory(category: AnnouncementCategory?) {
        val current = mutableState.value as? State.Success ?: return
        preferences.categoryFilter().set(category?.name.orEmpty())
        mutableState.value = current.copy(selectedCategory = category)
    }

    fun selectYear(year: Int?) {
        val current = mutableState.value as? State.Success ?: return
        preferences.yearFilter().set(year?.toString().orEmpty())
        mutableState.value = current.copy(selectedYear = year)
    }

    fun setSort(sort: AnnouncementSort) {
        val current = mutableState.value as? State.Success ?: return
        preferences.sort().set(sort)
        mutableState.value = current.copy(sort = sort)
    }

    fun setIncludeAdult(include: Boolean) {
        val current = mutableState.value as? State.Success ?: return
        preferences.includeAdult().set(include)
        mutableState.value = current.copy(includeAdult = include)
    }

    fun setLockBanner(locked: Boolean) {
        val current = mutableState.value as? State.Success ?: return
        preferences.lockBanner().set(locked)
        mutableState.value = current.copy(lockBanner = locked)
    }

    fun setShowCatalog(show: Boolean) {
        val current = mutableState.value as? State.Success ?: return
        preferences.showCatalog().set(show)
        mutableState.value = current.copy(showCatalog = show)
    }

    fun setAutoRefresh(autoRefresh: AnnouncementAutoRefresh) {
        val current = mutableState.value as? State.Success ?: return
        preferences.autoRefresh().set(autoRefresh)
        mutableState.value = current.copy(autoRefresh = autoRefresh)
    }

    /** Clears only the content filters (category + year); used by the empty state. */
    fun clearFilters() {
        val current = mutableState.value as? State.Success ?: return
        preferences.categoryFilter().set("")
        preferences.yearFilter().set("")
        mutableState.value = current.copy(selectedCategory = null, selectedYear = null)
    }

    fun resetFilters() {
        val current = mutableState.value as? State.Success ?: return
        preferences.categoryFilter().set("")
        preferences.yearFilter().set("")
        preferences.sort().set(AnnouncementSort.AIRING_SOON)
        preferences.includeAdult().set(false)
        preferences.lockBanner().set(false)
        preferences.showCatalog().set(true)
        preferences.autoRefresh().set(AnnouncementAutoRefresh.OFF)
        mutableState.value = current.copy(
            selectedCategory = null,
            selectedYear = null,
            sort = AnnouncementSort.AIRING_SOON,
            includeAdult = false,
            lockBanner = false,
            showCatalog = true,
            autoRefresh = AnnouncementAutoRefresh.OFF,
        )
    }
}