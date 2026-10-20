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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CoverWidth = 82.dp
private val CoverHeight = 116.dp
private val StarColor = Color(0xFFFFB938)

/** Static scrim shared by every featured card; built once, never re-allocated. */
private val FeaturedScrim = Brush.verticalGradient(
    0f to Color.Transparent,
    0.35f to Color.Black.copy(alpha = 0.10f),
    1f to Color.Black.copy(alpha = 0.86f),
)

/**
 * A flat, tonal list card. No elevation/shadow, fixed image size (so Coil decodes a small
 * bitmap) and bounded text lines (so measurement stays cheap while scrolling).
 */
@Composable
fun AnnouncementCard(
    entry: AnnouncementEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val release = releaseInfo(entry)
    val placeholder = entry.coverTint()?.copy(alpha = 0.45f) ?: colors.surfaceContainerHighest

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = AnnouncementCardShape,
        color = colors.surfaceContainerLow,
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            AnnouncementCover(
                url = entry.coverImageUrl,
                contentDescription = entry.title,
                placeholder = placeholder,
                modifier = Modifier.size(width = CoverWidth, height = CoverHeight),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = CoverHeight)
                    .padding(start = 14.dp, top = 2.dp, end = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryPill(category = entry.category)
                        if (entry.isAdult) {
                            Spacer(modifier = Modifier.width(6.dp))
                            AdultBadge()
                        }
                    }
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (entry.description.isNotBlank()) {
                        Text(
                            text = entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReleasePill(info = release)
                    Spacer(modifier = Modifier.weight(1f))
                    val score = entry.score
                    if (score != null && score > 0) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Score",
                            tint = StarColor,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "$score%",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Large "Up next" hero for the soonest dated release. One image + one static gradient:
 * looks rich, costs about the same as a regular card.
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

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(212.dp),
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
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "UP NEXT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = colors.onPrimary,
                        modifier = Modifier
                            .background(colors.primary, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CategoryPill(category = entry.category, onImage = true)
                }
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReleasePill(info = release)
                    if (date != null) {
                        Text(
                            text = date,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdultBadge() {
    Text(
        text = "18+",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
