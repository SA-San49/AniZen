package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.GlobalSearchScreen
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.bulkSelectionButton
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.util.system.toast
import mihon.feature.airingschedule.match.ScheduleManualMatchStore
import tachiyomi.domain.anime.model.Anime
import tachiyomi.presentation.core.screens.LoadingScreen

class GlobalSearchScreen(
    val searchQuery: String = "",
    private val extensionFilter: String? = null,
    /**
     * Set when the Airing Schedule opened this search for an entry. The anime the user picks
     * (here or in a source opened from here) becomes that entry's manual match, which
     * overrides every automatic library title match.
     */
    private val scheduleBinding: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val screenModel = rememberScreenModel {
            GlobalSearchScreenModel(
                initialQuery = searchQuery,
                initialExtensionFilter = extensionFilter,
            )
        }
        val state by screenModel.state.collectAsState()

        val bulkFavoriteScreenModel = rememberScreenModel { BulkFavoriteScreenModel() }
        val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

        var showSingleLoadingScreen by remember {
            mutableStateOf(
                searchQuery.isNotEmpty() && !extensionFilter.isNullOrEmpty() && state.total == 1,
            )
        }

        if (showSingleLoadingScreen) {
            LoadingScreen()

            LaunchedEffect(state.items) {
                when (val result = state.items.values.singleOrNull()) {
                    SearchItemResult.Loading -> return@LaunchedEffect
                    is SearchItemResult.Success -> {
                        val anime = result.result.singleOrNull()
                        if (anime != null) {
                            navigator.replace(AnimeScreen(anime.id, true))
                        } else {
                            // Backoff to result screen
                            showSingleLoadingScreen = false
                        }
                    }
                    else -> showSingleLoadingScreen = false
                }
            }
        } else {
            GlobalSearchScreen(
                state = state,
                navigateUp = navigator::pop,
                onChangeSearchQuery = screenModel::updateSearchQuery,
                onSearch = { screenModel.search() },
                getAnime = { screenModel.getAnime(it) },
                onChangeSearchFilter = screenModel::setSourceFilter,
                onToggleResults = screenModel::toggleFilterResults,
                onClickSource = {
                    navigator.push(
                        BrowseSourceScreen(it.id, state.searchQuery, scheduleBinding = scheduleBinding),
                    )
                },
                onClickItem = { anime ->
                    if (bulkFavoriteState.selectionMode) {
                        bulkFavoriteScreenModel.toggleSelection(anime)
                    } else {
                        if (scheduleBinding != null) {
                            bindScheduleManualMatch(context, scheduleBinding, anime)
                        }
                        navigator.push(AnimeScreen(anime.id, true))
                    }
                },
                onLongClickItem = { anime ->
                    if (!bulkFavoriteState.selectionMode) {
                        bulkFavoriteScreenModel.toggleSelectionMode(true)
                    }
                    bulkFavoriteScreenModel.toggleSelection(anime)
                },
                selection = bulkFavoriteState.selection,
                onClearSelection = { bulkFavoriteScreenModel.toggleSelectionMode(false) },
                onChangeCategory = bulkFavoriteScreenModel::addFavorite,
            )

            BulkFavoriteDialogs(
                bulkFavoriteScreenModel = bulkFavoriteScreenModel,
                dialog = bulkFavoriteState.dialog,
            )
        }
    }
}

/**
 * Saves [anime] as the user's manual pick for the schedule entry encoded in [scheduleBinding].
 * Shared by Global Search and sources opened from a schedule-launched Global Search.
 */
internal fun bindScheduleManualMatch(context: Context, scheduleBinding: String, anime: Anime) {
    val saved = runCatching { ScheduleManualMatchStore().bind(scheduleBinding, anime) }.getOrDefault(false)
    if (saved) {
        context.toast("Schedule will now open \"${anime.title}\"")
    }
}
