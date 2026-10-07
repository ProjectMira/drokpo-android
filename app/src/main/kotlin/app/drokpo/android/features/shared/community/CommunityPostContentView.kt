package app.drokpo.android.features.shared.community

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.PhotoBand
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Poll
import app.drokpo.android.core.model.PollOption
import app.drokpo.android.ui.components.ProminentButton
import app.drokpo.android.ui.components.SecondaryButton
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold
import java.time.ZoneId

/**
 * Renders one community post's content — used both in a community's own post feed/page
 * (CommunityPageScreen, CommunitiesScreen) and, interleaved, in the Discover deck — so the surfaces
 * stay visually consistent. Port of CommunityPostContentView (CONTRACT §B.9).
 *
 * Each callback null ⇒ that affordance hidden/disabled:
 * - [onVote]: called with the tapped option id; null disables voting (read-only contexts). The
 *   caller owns the network call and updates `post`.
 * - [onRsvp]: called with `going`; null hides the Join / Can't come button. The caller owns the
 *   network call and updates `post`.
 * - [onOpenLink]: called when the link CTA is tapped; null hides the button (it also needs a link).
 * - [onOpenComments]: called when the comment button is tapped; null hides the button (e.g. the
 *   compact deck card, where comments live one tap away in the sheet).
 * - [onOpenCommunity]: header tap → the community's page. iOS makes the header a NavigationLink
 *   whenever the post carries a communityId (every call site sits inside a navigation context),
 *   so every caller should pass it.
 */
@Composable
fun CommunityPostContentView(
    post: CommunityPostCard,
    modifier: Modifier = Modifier,
    onVote: ((optionId: String) -> Unit)? = null,
    onRsvp: ((going: Boolean) -> Unit)? = null,
    onOpenLink: (() -> Unit)? = null,
    onOpenComments: (() -> Unit)? = null,
    onOpenCommunity: ((cid: String) -> Unit)? = null,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PostHeader(post, onOpenCommunity)
        post.displayPhotos.firstOrNull()?.let { photo ->
            // Fixed-aspect clipped band — an unclipped fill image inflates the surrounding layout
            // (see PhotoBand).
            PhotoBand(
                photo = photo,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        post.title?.takeIf { it.isNotEmpty() }?.let { title ->
            Text(title, style = typography.headline, color = colors.label)
        }
        if (post.kind == "event") {
            EventDetailsView(post, onRsvp)
        }
        post.body?.takeIf { it.isNotEmpty() }?.let { body ->
            Text(body, style = typography.subheadline, color = colors.secondaryLabel)
        }
        val poll = post.poll
        if (post.kind == "poll" && poll != null) {
            PollOptionsView(poll = poll, myVote = post.myVote, onVote = onVote)
        }
        if (hasLink(post) && onOpenLink != null) {
            ProminentButton(text = ctaLabel(post), onClick = onOpenLink)
        }
        if (onOpenComments != null) {
            CommentButton(post.commentCount, onOpenComments)
        }
    }
}

@Composable
private fun CommentButton(commentCount: Int?, onClick: () -> Unit) {
    val colors = DrokpoTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ChatBubbleOutline,
            contentDescription = "Comments",
            tint = colors.secondaryLabel,
            // SF "bubble.right" has its tail on the right; Material's sits bottom-left — mirror it.
            modifier = Modifier
                .size(17.dp)
                .graphicsLayer(scaleX = -1f),
        )
        Text(commentButtonLabel(commentCount), style = DrokpoTheme.typography.subheadline, color = colors.secondaryLabel)
    }
}

/**
 * Tappable when the post carries a communityId and the caller can push its page — every call site
 * (CommunitiesScreen's feed, the Likes saved-post detail, and CommunityPostDetailSheet, which owns
 * its own NavHost) sits inside a navigation context it can push onto.
 */
@Composable
private fun PostHeader(post: CommunityPostCard, onOpenCommunity: ((cid: String) -> Unit)?) {
    val colors = DrokpoTheme.colors
    val cid = post.communityId
    val open: (() -> Unit)? = if (cid != null && onOpenCommunity != null) {
        { onOpenCommunity(cid) }
    } else {
        null
    }
    val logo = remember(post.postId, post.communityLogoUrl) {
        post.communityLogoUrl?.let { Photo(storagePath = "logo-${post.postId}", url = it) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (open != null) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = open)
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemotePhotoView(
            photo = logo,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape),
        )
        Text(
            post.communityName ?: "Community",
            style = DrokpoTheme.typography.subheadline.bold(),
            color = colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // Signal the header is a link to the community's page.
        if (open != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.secondaryLabel,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Date/location rows plus the Join / Can't come button for an event post. */
@Composable
internal fun EventDetailsView(post: CommunityPostCard, onRsvp: ((going: Boolean) -> Unit)?) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    val goingCount = post.attendeeCount ?: 0
    val isGoing = post.myRsvp ?: false
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        post.eventDate?.let { date ->
            val label = remember(date, locale) {
                formatEventDate(date, ZoneId.systemDefault(), locale, DateFormat.is24HourFormat(context))
            }
            IconLabel(Icons.Outlined.CalendarToday, label, typography.subheadline, colors.label)
        }
        post.location?.takeIf { it.isNotEmpty() }?.let { location ->
            IconLabel(Icons.Outlined.Place, location, typography.subheadline, colors.secondaryLabel)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(goingLabel(goingCount), style = typography.caption, color = colors.secondaryLabel)
            Spacer(Modifier.weight(1f))
            if (onRsvp != null) {
                if (isGoing) {
                    SecondaryButton(text = "Can't come", onClick = { onRsvp(false) })
                } else {
                    ProminentButton(text = "Join", onClick = { onRsvp(true) })
                }
            }
        }
    }
}

/** SwiftUI `Label(title, systemImage:)`: icon + text in the same style and colour. */
@Composable
private fun IconLabel(icon: ImageVector, text: String, style: TextStyle, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        Text(text, style = style, color = color)
    }
}

/**
 * Tappable poll options that animate to a percentage bar once the caller has voted
 * (myVote != null) or after a vote is cast. Voting again on a different option changes the vote
 * (the backend moves the count); only the currently-selected option is disabled.
 */
@Composable
internal fun PollOptionsView(poll: Poll, myVote: String?, onVote: ((optionId: String) -> Unit)?) {
    val total = poll.totalVotes
    // VStack(spacing: 8) — centre-aligned, so the vote count sits under the middle of the rows.
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        poll.options.forEach { option ->
            PollOptionRow(
                option = option,
                poll = poll,
                myVote = myVote,
                enabled = onVote != null && myVote != option.id,
                onClick = { onVote?.invoke(option.id) },
            )
        }
        if (total > 0) {
            Text(voteCountLabel(total), style = DrokpoTheme.typography.caption, color = DrokpoTheme.colors.secondaryLabel)
        }
    }
}

@Composable
private fun PollOptionRow(
    option: PollOption,
    poll: Poll,
    myVote: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    val hasVoted = myVote != null
    val isMine = myVote == option.id
    val percentage = poll.percentage(option.id)
    val fraction by animateFloatAsState(
        targetValue = if (hasVoted) percentage.toFloat().coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "pollFraction",
    )
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(colors.fill)
            .semantics(mergeDescendants = true) { selected = isMine }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        if (hasVoted || fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(
                        if (isMine) {
                            colors.accent.copy(alpha = 0.35f)
                        } else {
                            colors.secondaryLabel.copy(alpha = colors.secondaryLabel.alpha * 0.2f)
                        },
                        shape,
                    ),
            )
        }
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    option.label,
                    style = typography.subheadline,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isMine) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Your vote",
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (hasVoted) {
                Text(
                    pollPercentLabel(percentage),
                    style = typography.caption,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
