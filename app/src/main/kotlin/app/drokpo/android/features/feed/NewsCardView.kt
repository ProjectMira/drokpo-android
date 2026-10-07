package app.drokpo.android.features.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * A news card in the Discover deck (port of iOS NewsCardView). News share
 * images are almost always landscape, so the photo is a full-width 16:9 band
 * on a dark backdrop (see [ContentCardLayout]).
 *
 * [onOpen] opens the source article in the in-app browser (arrow button) and
 * [onExpand] shows the full-summary detail sheet; both null when not the top card.
 */
@Composable
internal fun NewsCardView(
    item: NewsCard,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    onExpand: (() -> Unit)? = null,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    ContentCardLayout(
        photo = item.displayPhotos.firstOrNull(),
        badge = "News",
        modifier = modifier,
        // Whole card (photo, text) expands the detail sheet; the arrow button
        // keeps priority over this ancestor gesture.
        onTap = onExpand,
        tapLabel = "Show story",
        topEnd = {
            // Explicit "open the source" affordance — a button so it wins over
            // the card's tap-to-expand gesture.
            if (onOpen != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.photoScrimLight)
                        .clickable(role = Role.Button, onClickLabel = "Read the full story", onClick = onOpen),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ArrowOutward,
                        contentDescription = "Open article",
                        tint = colors.onPhoto,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
    ) {
        val sourceName = item.sourceName?.takeIf { it.isNotEmpty() }
        val relative = item.relativePublished
        if (sourceName != null || relative != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sourceName?.let { CardText(it.uppercase(), style = typography.caption.bold(), alpha = 0.85f, maxLines = 1) }
                relative?.let { CardText("· $it", style = typography.caption, alpha = 0.7f, maxLines = 1) }
            }
        }
        CardText(item.title ?: "—", style = typography.title2.bold(), maxLines = 3)
        item.gist?.takeIf { it.isNotEmpty() }?.let { gist ->
            CardText(gist, style = typography.subheadline, alpha = 0.95f, maxLines = 3)
        }
        if (onOpen != null) {
            CardLabel(
                text = "Swipe right to save · arrow to read",
                icon = Icons.Filled.Swipe,
                style = typography.caption.bold(),
                alpha = 0.8f,
                iconSize = 14.dp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
