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
import mihon.feature.airingschedule.SchedulePickRequest
import mihon.feature.airingschedule.recordSchedulePick
import tachiyomi.presentation.core.screens.LoadingScreen

class GlobalSearchScreen(
    val searchQuery: String = "",
    private val extensionFilter: String? = null,
    /** Set when opened from the airing schedule: the anime the user picks becomes that entry's manual match. */
    private val schedulePick: SchedulePickRequest? = null,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow

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
                            schedulePick?.let { recordSchedulePick(it, anime) }
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
                    navigator.push(BrowseSourceScreen(it.id, state.searchQuery, schedulePick = schedulePick))
                },
                onClickItem = { anime ->
                    if (bulkFavoriteState.selectionMode) {
                        bulkFavoriteScreenModel.toggleSelection(anime)
                    } else {
                        schedulePick?.let { recordSchedulePick(it, anime) }
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
