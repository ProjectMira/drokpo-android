package app.drokpo.android.features.feed

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandCircleDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * A community post card in the Discover deck — same banded layout as
 * NewsCardView (post images are usually landscape too): photo band on a
 * dark backdrop, bottom fade carrying the text above the deck's overlaid
 * buttons. Voting itself only happens in the expanded detail sheet
 * (CommunityPostContentView), so a poll's option taps never fight the
 * card's drag-to-swipe gesture. (Port of iOS CommunityPostCardView.)
 *
 * [onOpen] opens the post's link (CTA); null when the post has no link or this
 * isn't the top card. [onExpand] shows the full detail sheet (poll voting, full
 * body); null when not the top card.
 */
@Composable
internal fun CommunityPostCardView(
    post: CommunityPostCard,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    onExpand: (() -> Unit)? = null,
) {
    val typography = DrokpoTheme.typography
    ContentCardLayout(
        photo = post.displayPhotos.firstOrNull(),
        badge = "Community",
        modifier = modifier,
        // Whole card (photo, chevron, text) expands the detail sheet; the CTA
        // button keeps priority over this ancestor gesture.
        onTap = onExpand,
        tapLabel = "Show post",
        topEnd = {
            if (onExpand != null) {
                Icon(
                    Icons.Filled.ExpandCircleDown,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(26.dp)
                        .rotate(180f),
                )
            }
        },
    ) {
        post.communityName?.takeIf { it.isNotEmpty() }?.let { name ->
            CardText(name.uppercase(), style = typography.caption.bold(), alpha = 0.85f, maxLines = 1)
        }
        CardText(post.title ?: "—", style = typography.title2.bold(), maxLines = 3)
        val eventDate = post.eventDate
        if (post.kind == "event" && eventDate != null) {
            CardLabel(formatEventDate(eventDate), icon = Icons.Filled.CalendarMonth, style = typography.subheadline.bold())
        }
        post.body?.takeIf { it.isNotEmpty() }?.let { body ->
            CardText(body, style = typography.subheadline, alpha = 0.95f, maxLines = 3)
        }
        when {
            post.kind == "event" -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardText("${post.attendeeCount ?: 0} going", style = typography.subheadline, alpha = 0.9f)
                Spacer(Modifier.weight(1f))
                if (onExpand != null) {
                    Spacer(Modifier.width(8.dp))
                    // Right-swipe only ever RSVPs *in*; cancelling happens in
                    // the detail sheet — say so.
                    CardText(
                        if (post.myRsvp == true) "You're going ✓ — tap for details" else "Swipe right to join",
                        style = typography.caption.bold(),
                        maxLines = 1,
                    )
                }
            }
            post.kind == "poll" -> CardLabel(
                "Tap to vote",
                icon = Icons.Filled.BarChart,
                style = typography.subheadline.bold(),
                modifier = Modifier.padding(top = 2.dp),
            )
            onOpen != null -> CardCallToAction(label = post.ctaLabel, onClick = onOpen)
        }
    }
}
