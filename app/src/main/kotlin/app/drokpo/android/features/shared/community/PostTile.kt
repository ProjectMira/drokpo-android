package app.drokpo.android.features.shared.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/** SF-symbol equivalents: link, chart.bar.fill, calendar, megaphone.fill. */
internal val PostKind.icon: ImageVector
    get() = when (this) {
        PostKind.Link -> Icons.Filled.Link
        PostKind.Poll -> Icons.Filled.BarChart
        PostKind.Event -> Icons.Filled.CalendarToday
        PostKind.Announcement -> Icons.Filled.Campaign
    }

/** iOS `.blue` / `.purple` / `.green` / `.accentColor` (system colours, light/dark). */
internal val PostKind.tint: Color
    @Composable @ReadOnlyComposable
    get() {
        val colors = DrokpoTheme.colors
        return when (this) {
            PostKind.Link -> if (colors.isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
            PostKind.Poll -> if (colors.isDark) Color(0xFFBF5AF2) else Color(0xFFAF52DE)
            PostKind.Event -> colors.green
            PostKind.Announcement -> colors.accent
        }
    }

/**
 * One square grid tile: the post's photo if it has one, otherwise a typed placeholder (kind icon +
 * title snippet) so imageless posts (polls, plain announcements) still show up in the grid instead
 * of being hidden. In owner mode an inactive post gets the "Unpublished" badge and is dimmed.
 */
@Composable
internal fun PostTile(
    post: CommunityPostCard,
    showUnpublishedBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val isUnpublished = showUnpublishedBadge && post.active == false
    val kind = postKind(post.kind)
    val photo = post.displayPhotos.firstOrNull()
    Box(
        modifier
            .aspectRatio(1f)
            .clipToBounds()
            .alpha(if (isUnpublished) 0.55f else 1f)
            .clickable(role = Role.Button, onClick = onClick)
            .then(
                // A photo tile has no visible text; give TalkBack the title (placeholder tiles
                // already read theirs).
                if (photo != null) {
                    Modifier.semantics { contentDescription = post.title ?: "Post" }
                } else {
                    Modifier
                },
            ),
    ) {
        if (photo != null) {
            RemotePhotoView(photo = photo, modifier = Modifier.fillMaxSize())
            ShadowedIcon(
                kind.icon,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            )
        } else {
            PlaceholderTile(post, kind)
        }
        if (isUnpublished) {
            Text(
                "Unpublished",
                style = DrokpoTheme.typography.caption2.bold(),
                color = colors.onPhoto,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(colors.orange, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun PlaceholderTile(post: CommunityPostCard, kind: PostKind) {
    val tint = kind.tint
    Box(
        Modifier
            .fillMaxSize()
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(kind.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Text(
                post.title ?: "",
                style = DrokpoTheme.typography.caption2.bold(),
                color = DrokpoTheme.colors.label,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/** The kind glyph over a photo: white, caption2-sized, with iOS `.shadow(radius: 2)`. */
@Composable
private fun ShadowedIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier) {
        // Soft dark copy underneath (blur is a no-op below API 31, where it's simply hidden behind
        // the white glyph).
        Icon(
            icon,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier
                .size(13.dp)
                .offset(y = 0.5.dp)
                .blur(2.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
        )
        Icon(icon, contentDescription = null, tint = DrokpoTheme.colors.onPhoto, modifier = Modifier.size(13.dp))
    }
}
