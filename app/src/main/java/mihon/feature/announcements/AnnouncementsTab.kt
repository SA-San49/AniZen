package mihon.feature.announcements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

object AnnouncementsTab {

    @Composable
    fun Content(
        contentPadding: PaddingValues,
        screenModel: AnnouncementsScreenModel,
    ) {
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        when (val current = state) {
            is AnnouncementsScreenModel.State.Loading -> LoadingState(contentPadding)
            is AnnouncementsScreenModel.State.Error -> ErrorState(
                contentPadding = contentPadding,
                hasCache = current.cachedEntries.isNotEmpty(),
                onRetry = { screenModel.load(forceRefresh = true) },
                onShowCached = { screenModel.showCachedData() },
            )
            is AnnouncementsScreenModel.State.Success -> SuccessState(
                contentPadding = contentPadding,
                state = current,
                isRefreshing = screenModel.isRefreshing,
                onRefresh = { screenModel.load(forceRefresh = true) },
                onCardClick = { entry -> navigator.push(AnnouncementDetailScreen(entry)) },
                onSelectCategory = screenModel::selectCategory,
                onOpenFilters = screenModel::openFilters,
                onClearFilters = screenModel::clearFilters,
            )
        }

        if (screenModel.showFiltersDialog && state is AnnouncementsScreenModel.State.Success) {
            AnnouncementsFilterSheet(
                state = state as AnnouncementsScreenModel.State.Success,
                onDismissRequest = screenModel::closeFilters,
                onSelectCategory = screenModel::selectCategory,
                onSelectSort = screenModel::setSort,
                onSelectYear = screenModel::selectYear,
                onSetIncludeAdult = screenModel::setIncludeAdult,
                onSetAutoRefresh = screenModel::setAutoRefresh,
                onReset = screenModel::resetFilters,
            )
        }
    }

    // region Success

    @Composable
    private fun SuccessState(
        contentPadding: PaddingValues,
        state: AnnouncementsScreenModel.State.Success,
        isRefreshing: Boolean,
        onRefresh: () -> Unit,
        onCardClick: (AnnouncementEntry) -> Unit,
        onSelectCategory: (AnnouncementCategory?) -> Unit,
        onOpenFilters: () -> Unit,
        onClearFilters: () -> Unit,
    ) {
        val entries = state.filteredEntries
        // The hero carousel only makes sense when the list is ordered by release date
        val carousel = rememberCarouselEntries(entries, enabled = state.sort == AnnouncementSort.AIRING_SOON)
        // Carousel entries live in the carousel, so keep them out of the list below (no duplicates)
        val rest = remember(entries, carousel) {
            if (carousel.isEmpty()) {
                entries
            } else {
                val shown = carousel.mapTo(HashSet(carousel.size * 2)) { it.mediaId }
                entries.filter { it.mediaId !in shown }
            }
        }
        val releasingThisWeek = remember(entries) {
            val now = System.currentTimeMillis()
            entries.count { entry -> entry.exactReleaseDate?.let { it > now && it - now < WEEK_MS } == true }
        }

        PullRefresh(
            refreshing = isRefreshing,
            enabled = true,
            onRefresh = onRefresh,
            indicatorPadding = contentPadding,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding + PaddingValues(bottom = 16.dp),
            ) {
                item(key = "quick_filters", contentType = "quick_filters") {
                    QuickCategoryRow(
                        selected = state.selectedCategory,
                        counts = state.categoryCounts,
                        onSelect = onSelectCategory,
                    )
                }
                item(key = "summary", contentType = "summary") {
                    SummaryRow(
                        count = entries.size,
                        releasingThisWeek = releasingThisWeek,
                        sort = state.sort,
                        onOpenFilters = onOpenFilters,
                    )
                }
                if (carousel.isNotEmpty()) {
                    item(key = "featured", contentType = "featured") {
                        FeaturedAnnouncementCarousel(
                            entries = carousel,
                            onEntryClick = onCardClick,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                    }
                }
                if (entries.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        EmptyState(
                            hasFilters = state.hasContentFilters,
                            onClearFilters = onClearFilters,
                            onRefresh = onRefresh,
                        )
                    }
                }
                items(
                    items = rest,
                    key = { it.mediaId },
                    contentType = { "announcement" },
                ) { entry ->
                    AnnouncementCard(
                        entry = entry,
                        onClick = { onCardClick(entry) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun QuickCategoryRow(
        selected: AnnouncementCategory?,
        counts: Map<AnnouncementCategory, Int>,
        onSelect: (AnnouncementCategory?) -> Unit,
    ) {
        val categories = remember(counts, selected) {
            AnnouncementCategory.entries.filter { (counts[it] ?: 0) > 0 || it == selected }
        }
        val total = remember(counts) { counts.values.sum() }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "all") {
                CategoryChip(
                    label = "All",
                    count = total,
                    selected = selected == null,
                    accent = null,
                    onClick = { onSelect(null) },
                )
            }
            items(categories, key = { it.name }) { category ->
                CategoryChip(
                    label = category.shortName(),
                    count = counts[category] ?: 0,
                    selected = selected == category,
                    accent = category.accent(),
                    // Tapping the active chip again goes back to "All"
                    onClick = { onSelect(if (selected == category) null else category) },
                )
            }
        }
    }

    @Composable
    private fun CategoryChip(
        label: String,
        count: Int,
        selected: Boolean,
        accent: Color?,
        onClick: () -> Unit,
    ) {
        val colors = MaterialTheme.colorScheme
        val container = if (selected) colors.primary else colors.surfaceContainerHigh
        val content = if (selected) colors.onPrimary else colors.onSurface
        Surface(
            selected = selected,
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (accent != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (selected) content else accent, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = content.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun SummaryRow(
        count: Int,
        releasingThisWeek: Int,
        sort: AnnouncementSort,
        onOpenFilters: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (count == 1) "1 announcement" else "$count announcements",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (releasingThisWeek) {
                        0 -> "Pull down to refresh"
                        1 -> "1 releasing this week"
                        else -> "$releasingThisWeek releasing this week"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenFilters) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Sort,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = sort.displayName())
            }
        }
    }

    @Composable
    private fun EmptyState(
        hasFilters: Boolean,
        onClearFilters: () -> Unit,
        onRefresh: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StateIcon(
                icon = Icons.Outlined.SearchOff,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (hasFilters) "Nothing matches these filters" else "No announcements yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = if (hasFilters) {
                    "Try another category or release year."
                } else {
                    "New seasons, sequels and movies will show up here."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            FilledTonalButton(
                onClick = if (hasFilters) onClearFilters else onRefresh,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(text = if (hasFilters) "Clear filters" else "Refresh")
            }
        }
    }

    // endregion

    // region Loading / Error

    /** Static skeleton: zero per-frame work apart from the thin progress bar. */
    @Composable
    private fun LoadingState(contentPadding: PaddingValues) {
        val block = MaterialTheme.colorScheme.surfaceContainerHigh
        val card = MaterialTheme.colorScheme.surfaceContainerLow
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(64.dp, 104.dp, 92.dp, 80.dp).forEach { chipWidth ->
                    Box(
                        modifier = Modifier
                            .size(width = chipWidth, height = 34.dp)
                            .background(block, CircleShape),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .height(212.dp)
                    .background(block, AnnouncementHeroShape),
            )
            repeat(3) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 5.dp)
                        .fillMaxWidth()
                        .background(card, AnnouncementCardShape)
                        .padding(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 82.dp, height = 116.dp)
                            .background(block, AnnouncementCoverShape),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 14.dp, top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SkeletonLine(width = 0.35f, color = block)
                        SkeletonLine(width = 0.9f, color = block)
                        SkeletonLine(width = 0.7f, color = block)
                        SkeletonLine(width = 0.45f, color = block)
                    }
                }
            }
        }
    }

    @Composable
    private fun SkeletonLine(width: Float, color: Color) {
        Box(
            modifier = Modifier
                .fillMaxWidth(width)
                .height(12.dp)
                .background(color, RoundedCornerShape(6.dp)),
        )
    }

    @Composable
    private fun ErrorState(
        contentPadding: PaddingValues,
        hasCache: Boolean,
        onRetry: () -> Unit,
        onShowCached: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StateIcon(
                icon = Icons.Outlined.CloudOff,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "Couldn't reach AniList",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = "The service may be down or rate limiting requests. Give it a moment and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Try again")
            }
            if (hasCache) {
                OutlinedButton(onClick = onShowCached, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Show saved announcements")
                }
            }
        }
    }

    @Composable
    private fun StateIcon(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        container: Color,
        content: Color,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(container, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(36.dp),
            )
        }
    }

    // endregion

    // region Filter sheet

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun AnnouncementsFilterSheet(
        state: AnnouncementsScreenModel.State.Success,
        onDismissRequest: () -> Unit,
        onSelectCategory: (AnnouncementCategory?) -> Unit,
        onSelectSort: (AnnouncementSort) -> Unit,
        onSelectYear: (Int?) -> Unit,
        onSetIncludeAdult: (Boolean) -> Unit,
        onSetAutoRefresh: (AnnouncementAutoRefresh) -> Unit,
        onReset: () -> Unit,
    ) {
        AdaptiveSheet(onDismissRequest = onDismissRequest) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.strings.action_filter),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = onReset) {
                        Text(text = stringResource(MR.strings.action_reset))
                    }
                }

                SheetSection(title = stringResource(MR.strings.action_sort)) {
                    AnnouncementSort.entries.forEach { sort ->
                        FilterChip(
                            selected = state.sort == sort,
                            onClick = { onSelectSort(sort) },
                            label = { Text(sort.displayName()) },
                        )
                    }
                }

                SheetSection(title = "Category") {
                    FilterChip(
                        selected = state.selectedCategory == null,
                        onClick = { onSelectCategory(null) },
                        label = { Text("All") },
                    )
                    AnnouncementCategory.entries.forEach { category ->
                        FilterChip(
                            selected = state.selectedCategory == category,
                            onClick = { onSelectCategory(category) },
                            label = { Text(category.displayName()) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(category.accent(), CircleShape),
                                )
                            },
                        )
                    }
                }

                if (state.availableYears.isNotEmpty()) {
                    SheetSection(title = "Release year") {
                        FilterChip(
                            selected = state.selectedYear == null,
                            onClick = { onSelectYear(null) },
                            label = { Text("Any") },
                        )
                        state.availableYears.forEach { year ->
                            FilterChip(
                                selected = state.selectedYear == year,
                                onClick = { onSelectYear(year) },
                                label = { Text(year.toString()) },
                            )
                        }
                    }
                }

                Surface(
                    onClick = { onSetIncludeAdult(!state.includeAdult) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Include 18+ content",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "Show adult and NSFW announcements",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.includeAdult,
                            onCheckedChange = onSetIncludeAdult,
                        )
                    }
                }

                SheetSection(title = "Auto refresh") {
                    AnnouncementAutoRefresh.entries.forEach { refresh ->
                        FilterChip(
                            selected = state.autoRefresh == refresh,
                            onClick = { onSetAutoRefresh(refresh) },
                            label = { Text(refresh.displayName()) },
                        )
                    }
                }

                val resultCount = state.filteredEntries.size
                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .height(48.dp),
                ) {
                    Text(
                        text = if (resultCount == 1) "Show 1 result" else "Show $resultCount results",
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SheetSection(
        title: String,
        content: @Composable () -> Unit,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }

    // endregion

    private fun AnnouncementSort.displayName(): String = when (this) {
        AnnouncementSort.AIRING_SOON -> "Airing soon"
        AnnouncementSort.LATEST_ADDED -> "Latest added"
        AnnouncementSort.POPULARITY -> "Popularity"
        AnnouncementSort.SCORE -> "Score"
        AnnouncementSort.TITLE -> "Title"
    }

    private fun AnnouncementAutoRefresh.displayName(): String = when (this) {
        AnnouncementAutoRefresh.OFF -> "Off"
        AnnouncementAutoRefresh.SIX_HOURS -> "6h"
        AnnouncementAutoRefresh.TWELVE_HOURS -> "12h"
        AnnouncementAutoRefresh.ONE_DAY -> "24h"
        AnnouncementAutoRefresh.TWO_DAYS -> "2d"
        AnnouncementAutoRefresh.FIVE_DAYS -> "5d"
        AnnouncementAutoRefresh.SEVEN_DAYS -> "7d"
    }
}
