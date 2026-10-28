package mihon.feature.announcements

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Shared visual language for the Announcements feature.
 *
 * Everything here is intentionally cheap to draw: flat tonal surfaces, no shadows,
 * no blur, no infinite animations. Shapes are drawn with background(shape) rather
 * than clip() wherever possible so no extra graphics layers are created.
 */

internal val AnnouncementCardShape = RoundedCornerShape(24.dp)
internal val AnnouncementCoverShape = RoundedCornerShape(16.dp)
internal val AnnouncementHeroShape = RoundedCornerShape(32.dp)

/** Hero height, shared with the loading skeleton so nothing jumps when data arrives. */
internal val AnnouncementHeroHeight = 244.dp

private val StarColor = Color(0xFFFFB938)

/** Frosted look for chips that sit on top of artwork: translucent fill + hairline edge. */
private val GlassFill = Color.White.copy(alpha = 0.16f)
private val GlassEdge = Color.White.copy(alpha = 0.24f)

internal fun Modifier.glassPill(): Modifier = this
    .background(GlassFill, CircleShape)
    .border(0.75.dp, GlassEdge, CircleShape)

internal fun AnnouncementCategory.displayName(): String = when (this) {
    AnnouncementCategory.NEW_SEASON -> "New Season"
    AnnouncementCategory.ADAPTATION -> "Adaptation"
    AnnouncementCategory.SPIN_OFF -> "Spin-off"
    AnnouncementCategory.REMAKE -> "Remake"
    AnnouncementCategory.ORIGINAL -> "Original"
    AnnouncementCategory.MOVIE -> "Movie"
    AnnouncementCategory.SPECIAL -> "Special / OVA / ONA"
}

/** Compact label used on pills and chips where horizontal space is tight. */
internal fun AnnouncementCategory.shortName(): String = when (this) {
    AnnouncementCategory.SPECIAL -> "Special"
    else -> displayName()
}

/** Mid-tone accents that read well as a dot / tint on both light and dark themes. */
internal fun AnnouncementCategory.accent(): Color = when (this) {
    AnnouncementCategory.NEW_SEASON -> Color(0xFFF2A93B)
    AnnouncementCategory.ADAPTATION -> Color(0xFF5B8DEF)
    AnnouncementCategory.SPIN_OFF -> Color(0xFF9B7BFF)
    AnnouncementCategory.REMAKE -> Color(0xFF26B6A4)
    AnnouncementCategory.ORIGINAL -> Color(0xFF8FA3B0)
    AnnouncementCategory.MOVIE -> Color(0xFFF0607F)
    AnnouncementCategory.SPECIAL -> Color(0xFFC07BE0)
}

/** AniList provides a dominant cover colour (e.g. "#e4a15d"); used as an instant image placeholder. */
internal fun AnnouncementEntry.coverTint(): Color? {
    val hex = coverColor?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    val rgb = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or rgb)
}

internal enum class ReleaseUrgency { NOW, SOON, LATER, UNKNOWN }

@Immutable
internal data class ReleaseInfo(val label: String, val urgency: ReleaseUrgency)

private const val MINUTE_MS = 60 * 1000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
internal const val WEEK_MS = 7 * DAY_MS

private val shortDateFormatter: DateTimeFormatter by lazy {
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
}
private val longDateFormatter: DateTimeFormatter by lazy {
    DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())
}

internal fun releaseInfo(entry: AnnouncementEntry, now: Long = System.currentTimeMillis()): ReleaseInfo {
    val exact = entry.exactReleaseDate
        ?: return entry.expectedYear
            ?.let { ReleaseInfo("Expected $it", ReleaseUrgency.LATER) }
            ?: ReleaseInfo("Date TBA", ReleaseUrgency.UNKNOWN)

    val remaining = exact - now
    if (remaining <= 0) return ReleaseInfo("Out now", ReleaseUrgency.NOW)

    val days = remaining / DAY_MS
    val hours = (remaining / HOUR_MS) % 24
    val minutes = (remaining / MINUTE_MS) % 60
    val label = when {
        days >= 30 -> shortDateFormatter.format(Instant.ofEpochMilli(exact).atZone(ZoneOffset.UTC))
        days > 0 -> "In ${days}d ${hours}h"
        hours > 0 -> "In ${hours}h ${minutes}m"
        else -> "In ${minutes.coerceAtLeast(1)}m"
    }
    return ReleaseInfo(label, if (remaining < WEEK_MS) ReleaseUrgency.SOON else ReleaseUrgency.LATER)
}

/** Release dates are stored as UTC midnight, so format in UTC to avoid off-by-one days. */
internal fun releaseDateText(entry: AnnouncementEntry): String? =
    entry.exactReleaseDate?.let { longDateFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC)) }

internal fun AnnouncementEntry.isUpcomingWithDate(now: Long = System.currentTimeMillis()): Boolean =
    exactReleaseDate?.let { it > now } == true

internal fun formatCompactNumber(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000f).replace(".0M", "M")
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000f).replace(".0K", "K")
    else -> value.toString()
}

internal fun AnnouncementEntry.sourceLabel(): String =
    if (fetchedFrom.startsWith("MAL")) "MAL" else "AniList"

@Composable
internal fun CategoryPill(
    category: AnnouncementCategory,
    modifier: Modifier = Modifier,
    onImage: Boolean = false,
) {
    val accent = category.accent()
    val content = if (onImage) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .then(if (onImage) Modifier.glassPill() else Modifier.background(accent.copy(alpha = 0.16f), CircleShape))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(accent, CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = category.shortName(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ReleasePill(
    info: ReleaseInfo,
    modifier: Modifier = Modifier,
    onImage: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val urgent = info.urgency == ReleaseUrgency.NOW || info.urgency == ReleaseUrgency.SOON
    val (background, content) = when {
        urgent -> colors.primary to colors.onPrimary
        onImage -> Color.Transparent to Color.White
        info.urgency == ReleaseUrgency.LATER -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .then(if (onImage && !urgent) Modifier.glassPill() else Modifier.background(background, CircleShape))
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (info.urgency == ReleaseUrgency.NOW) Icons.Filled.PlayArrow else Icons.Outlined.Schedule,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = info.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun AnnouncementCover(
    url: String?,
    contentDescription: String?,
    placeholder: Color,
    modifier: Modifier = Modifier,
    shape: Shape = AnnouncementCoverShape,
    outlined: Boolean = false,
    borderColor: Color? = null,
) {
    val stroke = borderColor ?: if (outlined) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) else null
    val base = modifier
        .clip(shape)
        .background(placeholder)
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = if (stroke != null) base.border(1.dp, stroke, shape) else base,
    )
}

/** Compact "★ 82%" badge. [onImage] switches to the frosted variant used on artwork. */
@Composable
internal fun ScoreBadge(
    score: Int,
    modifier: Modifier = Modifier,
    onImage: Boolean = false,
) {
    val content = if (onImage) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .then(
                if (onImage) {
                    Modifier.glassPill()
                } else {
                    Modifier.background(StarColor.copy(alpha = 0.16f), CircleShape)
                },
            )
            .padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = "Score",
            tint = StarColor,
            modifier = Modifier.size(13.dp),
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "$score%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1,
        )
    }
}
