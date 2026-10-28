package mihon.feature.announcements

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CoverWidth = 92.dp
private val CoverHeight = 132.dp
private val HeroPosterWidth = 78.dp
private val HeroPosterHeight = 112.dp
private val HeroPosterShape = RoundedCornerShape(14.dp)

/** Static scrims shared by every featured card; built once, never re-allocated. */
private val FeaturedScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.35f),
    0.28f to Color.Transparent,
    0.52f to Color.Black.copy(alpha = 0.18f),
    1f to Color.Black.copy(alpha = 0.90f),
)
private val FeaturedSideScrim = Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = 0.45f),
    0.65f to Color.Transparent,
)

/**
 * Modern list card: tonal surface with a soft wash of the cover's own colour, a larger
 * poster with the countdown sitting on it, and a compact meta row. Still flat (no shadow,
 * no blur), fixed image size and bounded text so scrolling stays cheap.
 */
@Composable
fun AnnouncementCard(
    entry: AnnouncementEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val release = releaseInfo(entry)
    val tint = entry.coverTint()
    val placeholder = tint?.copy(alpha = 0.45f) ?: colors.surfaceContainerHighest
    val wash = remember(tint) {
        tint?.let { Brush.horizontalGradient(listOf(it.copy(alpha = 0.20f), Color.Transparent)) }
    }

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = AnnouncementCardShape,
        color = colors.surfaceContainerLow,
    ) {
        Box {
            if (wash != null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(wash),
                )
            }
            Row(modifier = Modifier.padding(12.dp)) {
                Box(modifier = Modifier.size(width = CoverWidth, height = CoverHeight)) {
                    AnnouncementCover(
                        url = entry.coverImageUrl,
                        contentDescription = entry.title,
                        placeholder = placeholder,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (entry.isAdult) {
                        AdultBadge(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = CoverHeight)
                        .padding(start = 14.dp, top = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryPill(category = entry.category)
                            Spacer(modifier = Modifier.weight(1f))
                            val score = entry.score
                            if (score != null && score > 0) {
                                ScoreBadge(score = score)
                            }
                        }
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 20.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (entry.description.isNotBlank()) {
                            Text(
                                text = entry.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReleasePill(info = release)
                        Spacer(modifier = Modifier.weight(1f))
                        val popularity = entry.popularity
                        if (popularity != null && popularity > 0) {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = "Popularity",
                                tint = colors.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = formatCompactNumber(popularity),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Cinematic "Up next" hero: full-bleed banner, frosted chips, and the poster floating
 * next to the title. One banner + one small poster + two static gradients — looks rich,
 * costs about the same as a regular card.
 */
@Composable
fun FeaturedAnnouncementCard(
    entry: AnnouncementEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val release = releaseInfo(entry)
    val placeholder = entry.coverTint() ?: colors.surfaceContainerHighest
    val date = releaseDateText(entry)
    val showPoster = entry.bannerImageUrl != null && entry.coverImageUrl != null

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(AnnouncementHeroHeight),
        shape = AnnouncementHeroShape,
        color = placeholder,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnnouncementCover(
                url = entry.bannerImageUrl ?: entry.coverImageUrl,
                contentDescription = null,
                placeholder = placeholder,
                shape = AnnouncementHeroShape,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FeaturedScrim),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FeaturedSideScrim),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "UP NEXT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp,
                    color = colors.onPrimary,
                    modifier = Modifier
                        .background(colors.primary, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                CategoryPill(category = entry.category, onImage = true)
                Spacer(modifier = Modifier.weight(1f))
                val score = entry.score
                if (score != null && score > 0) {
                    ScoreBadge(score = score, onImage = true)
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (showPoster) {
                    AnnouncementCover(
                        url = entry.coverImageUrl,
                        contentDescription = entry.title,
                        placeholder = placeholder,
                        shape = HeroPosterShape,
                        borderColor = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(width = HeroPosterWidth, height = HeroPosterHeight),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 26.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (date != null) {
                        Text(
                            text = date,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    ReleasePill(
                        info = release,
                        onImage = true,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdultBadge(modifier: Modifier = Modifier) {
    Text(
        text = "18+",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
