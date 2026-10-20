package mihon.feature.announcements

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import kotlinx.parcelize.Parcelize
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Parcelize
data class AnnouncementDetailScreen(private val entry: AnnouncementEntry) : Screen, Parcelable {

    @Composable
    override fun Content() {
        val repository = remember { AnnouncementsRepository() }
        val extensionManager = remember { Injekt.get<ExtensionManager>() }
        val installedExtensions by extensionManager.installedExtensionsFlow.collectAsState()
        val context = LocalContext.current
        var isWatchlisted by remember { mutableStateOf(repository.isWatchlisted(entry.mediaId)) }
        var showSourceChooser by remember { mutableStateOf(false) }
        val navigator = LocalNavigator.currentOrThrow
        val relatedQuery = entry.relatedTitle ?: entry.title
        val placeholder = entry.coverTint() ?: MaterialTheme.colorScheme.surfaceContainerHighest

        Scaffold { contentPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    HeroHeader(
                        entry = entry,
                        placeholder = placeholder,
                        topInset = contentPadding.calculateTopPadding(),
                    )
                    Column(
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 20.dp,
                            bottom = 24.dp + contentPadding.calculateBottomPadding(),
                        ),
                    ) {
                        ReleaseCard(entry = entry)

                        WatchlistButton(
                            isWatchlisted = isWatchlisted,
                            onClick = {
                                repository.toggleWatchlist(entry.mediaId)
                                isWatchlisted = !isWatchlisted
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        )

                        StatsRow(entry = entry, modifier = Modifier.padding(top = 20.dp))

                        if (entry.description.isNotBlank()) {
                            SectionTitle("About")
                            Text(
                                text = entry.description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (entry.relatedTitle != null) {
                            SectionTitle("Related anime")
                            RelatedAnimeCard(
                                title = entry.relatedTitle,
                                year = entry.relatedYear,
                                onClick = { showSourceChooser = true },
                            )
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(
                                                "https://anilist.co/anime/${entry.relatedAniListMediaId ?: entry.mediaId}",
                                            ),
                                        ),
                                    )
                                },
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text("Open on AniList", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }

                BackButton(
                    onClick = { navigator.pop() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = contentPadding.calculateTopPadding() + 8.dp),
                )
            }
        }

        if (showSourceChooser) {
            SourceChooserDialog(
                query = relatedQuery,
                extensions = installedExtensions,
                onDismiss = { showSourceChooser = false },
                onSelectExtension = { extension ->
                    showSourceChooser = false
                    navigator.push(GlobalSearchScreen(relatedQuery, extension.pkgName))
                },
                onSearchAllSources = {
                    showSourceChooser = false
                    navigator.push(GlobalSearchScreen(relatedQuery))
                },
            )
        }
    }

    @Composable
    private fun HeroHeader(
        entry: AnnouncementEntry,
        placeholder: Color,
        topInset: Dp,
    ) {
        val background = MaterialTheme.colorScheme.background
        // Dark at the very top (status bar legibility), fading into the page colour at the bottom
        val scrim = remember(background) {
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.45f),
                0.3f to Color.Transparent,
                0.62f to background.copy(alpha = 0.65f),
                1f to background,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp + topInset),
        ) {
            AnnouncementCover(
                url = entry.bannerImageUrl ?: entry.coverImageUrl,
                contentDescription = null,
                placeholder = placeholder,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrim),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                AnnouncementCover(
                    url = entry.coverImageUrl,
                    contentDescription = entry.title,
                    placeholder = placeholder,
                    shape = RoundedCornerShape(16.dp),
                    outlined = true,
                    modifier = Modifier.size(width = 112.dp, height = 160.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, bottom = 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryPill(category = entry.category)
                        if (entry.isAdult) {
                            Text(
                                text = "18+",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun ReleaseCard(entry: AnnouncementEntry) {
        val colors = MaterialTheme.colorScheme
        val release = releaseInfo(entry)
        val highlighted = release.urgency == ReleaseUrgency.SOON || release.urgency == ReleaseUrgency.NOW
        val container = if (highlighted) colors.primaryContainer else colors.surfaceContainerLow
        val content = if (highlighted) colors.onPrimaryContainer else colors.onSurface
        val (headline, supporting) = when {
            entry.exactReleaseDate == null && entry.expectedYear != null ->
                "Expected in ${entry.expectedYear}" to "Exact date not announced yet"
            entry.exactReleaseDate == null -> "Release date TBA" to "We'll show it here as soon as it's announced"
            release.urgency == ReleaseUrgency.NOW -> "Out now" to releaseDateText(entry).orEmpty()
            release.label.startsWith("In ") ->
                "Releases ${release.label.replaceFirstChar { it.lowercase() }}" to releaseDateText(entry).orEmpty()
            else -> "Releases ${release.label}" to releaseDateText(entry).orEmpty()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(container, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(if (highlighted) colors.primary else colors.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = if (highlighted) colors.onPrimary else colors.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                )
                if (supporting.isNotEmpty()) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }

    @Composable
    private fun WatchlistButton(
        isWatchlisted: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val buttonModifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
        if (isWatchlisted) {
            FilledTonalButton(onClick = onClick, modifier = buttonModifier) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null)
                Text("Watching for release date", modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Button(onClick = onClick, modifier = buttonModifier) {
                Icon(Icons.Outlined.Notifications, contentDescription = null)
                Text("Add to watchlist", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    @Composable
    private fun StatsRow(entry: AnnouncementEntry, modifier: Modifier = Modifier) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatTile(
                label = "Year",
                value = entry.expectedYear?.toString() ?: "TBA",
                modifier = Modifier.weight(1f),
            )
            val score = entry.score
            if (score != null && score > 0) {
                StatTile(label = "Score", value = "$score%", modifier = Modifier.weight(1f))
            }
            val popularity = entry.popularity
            if (popularity != null && popularity > 0) {
                StatTile(label = "Fans", value = formatCompactNumber(popularity), modifier = Modifier.weight(1f))
            }
            StatTile(label = "Source", value = entry.sourceLabel(), modifier = Modifier.weight(1f))
        }
    }

    @Composable
    private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
        )
    }

    @Composable
    private fun RelatedAnimeCard(title: String, year: Int?, onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = year?.let { "$it · Tap to find it in your sources" } ?: "Tap to find it in your sources",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    @Composable
    private fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.35f),
            contentColor = Color.White,
            modifier = modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    @Composable
    private fun SourceChooserDialog(
        query: String,
        extensions: List<Extension.Installed>,
        onDismiss: () -> Unit,
        onSelectExtension: (Extension.Installed) -> Unit,
        onSearchAllSources: () -> Unit,
    ) {
        val sorted = remember(extensions) { extensions.sortedBy { it.name.lowercase() } }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Search related anime") },
            text = {
                // Scrollable so long extension lists never overflow the dialog
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Choose a source to search for “$query”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    sorted.forEach { extension ->
                        Surface(
                            onClick = { onSelectExtension(extension) },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = extension.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                    if (sorted.isEmpty()) {
                        Text(
                            text = "No installed anime extensions were found.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FilledTonalButton(
                        onClick = onSearchAllSources,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Search all sources")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            },
        )
    }
}
