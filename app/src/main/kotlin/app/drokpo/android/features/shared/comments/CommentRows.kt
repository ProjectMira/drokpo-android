package app.drokpo.android.features.shared.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.CommentCard
import app.drokpo.android.features.shared.audio.AudioBubbleView
import app.drokpo.android.ui.components.Spinner
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/** Row callbacks, bundled so the list can hand them to every row (and the catalog can use no-ops). */
@Immutable
internal data class CommentsActions(
    val onRefresh: () -> Unit = {},
    /** The last row appeared — page in older comments. */
    val onReachEnd: (CommentCard) -> Unit = {},
    val onReply: (CommentCard) -> Unit = {},
    /** "View n replies" on a top-level comment (its commentId). */
    val onShowReplies: (String) -> Unit = {},
    /** value = "like" / "dislike" / null (clear). */
    val onVote: (CommentCard, String?) -> Unit = { _, _ -> },
    val onDelete: (CommentCard) -> Unit = {},
    /** Report/block the comment's author (opens "Comment by …"). */
    val onSafety: (CommentCard) -> Unit = {},
)

/** Which row's swipe actions / long-press menu are open — one at a time, list-wide. */
internal class RowUiState(
    val openSwipeId: String?,
    val onOpenSwipeChange: (String?) -> Unit,
    val menuId: String?,
    val onMenuChange: (String?) -> Unit,
)

/** Avatar 32 + 8 spacing: comment bodies, actions and threads line up under the author name. */
private val CommentIndent = 40.dp
private val ReplyAvatar = 26.dp
private val ReplyIndent = 34.dp
private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 12.dp

/**
 * One top-level comment: header, body, Reply + votes, and its thread
 * (lazily loaded; once expanded there is no collapse control — iOS parity).
 */
@Composable
internal fun CommentRow(
    comment: CommentCard,
    state: CommentsUiState,
    actions: CommentsActions,
    rowUi: RowUiState,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val isExpanded = comment.commentId in state.expandedParents
    val replies = state.repliesByParent[comment.commentId]
    val canDelete = state.canDelete(comment)
    val canReport = state.canReport(comment)
    val replyCount = comment.replyCount ?: 0
    val thread = replies?.takeIf { isExpanded && it.isNotEmpty() }

    // iOS: list-row inset + the row's own 4pt. The padding lives inside the swipeable
    // part so its action buttons span the whole row height.
    Column(modifier.fillMaxWidth()) {
        SwipeActionsBox(
            id = comment.commentId,
            actions = rowSwipeActions(
                canDelete = canDelete,
                canReport = canReport,
                onDelete = { actions.onDelete(comment) },
                onReport = { actions.onSafety(comment) },
            ),
            openId = rowUi.openSwipeId,
            onOpenChange = rowUi.onOpenSwipeChange,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(
                        start = RowHorizontalPadding,
                        end = RowHorizontalPadding,
                        top = RowVerticalPadding,
                        bottom = if (thread != null) 4.dp else RowVerticalPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LongPressMenuArea(
                    id = comment.commentId,
                    items = longPressItems(canDelete, canReport, { actions.onDelete(comment) }, { actions.onSafety(comment) }),
                    rowUi = rowUi,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CommentHeader(comment)
                        CommentBody(comment, Modifier.padding(start = CommentIndent))
                    }
                }
                Row(
                    Modifier.padding(start = CommentIndent),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallTextButton("Reply", onClick = { actions.onReply(comment) })
                    Spacer(Modifier.weight(1f))
                    VoteButtons(comment, onVote = { value -> actions.onVote(comment, value) })
                }
                when {
                    isExpanded && replies == null -> Spinner(Modifier.padding(start = CommentIndent, top = 2.dp))
                    !isExpanded && replyCount > 0 -> SmallTextButton(
                        viewRepliesLabel(replyCount),
                        onClick = { actions.onShowReplies(comment.commentId) },
                        modifier = Modifier.padding(start = CommentIndent),
                    )
                }
            }
        }
        if (thread != null) {
            Column(
                Modifier.padding(top = 4.dp, bottom = RowVerticalPadding - 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                thread.forEach { reply ->
                    // Keyed like iOS ForEach(replies): a reply removed from the middle
                    // (delete, block, the backfill merge) must not hand its swipe state
                    // to the next one.
                    key(reply.commentId) {
                        ReplyRow(
                            reply = reply,
                            canDelete = state.canDelete(reply),
                            canReport = state.canReport(reply),
                            actions = actions,
                            rowUi = rowUi,
                        )
                    }
                }
            }
        }
    }
}

/** A reply inside an expanded thread: smaller avatar, votes, no Reply button (threads are one level deep). */
@Composable
private fun ReplyRow(
    reply: CommentCard,
    canDelete: Boolean,
    canReport: Boolean,
    actions: CommentsActions,
    rowUi: RowUiState,
) {
    SwipeActionsBox(
        id = reply.commentId,
        // iOS replies swipe to Delete only; reporting a reply goes through the long-press menu.
        actions = rowSwipeActions(canDelete = canDelete, canReport = false, onDelete = { actions.onDelete(reply) }, onReport = {}),
        openId = rowUi.openSwipeId,
        onOpenChange = rowUi.onOpenSwipeChange,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(DrokpoTheme.colors.background)
                .padding(start = RowHorizontalPadding + CommentIndent, end = RowHorizontalPadding, top = 2.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LongPressMenuArea(
                id = reply.commentId,
                items = longPressItems(canDelete, canReport, { actions.onDelete(reply) }, { actions.onSafety(reply) }),
                rowUi = rowUi,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommentHeader(reply, avatarSize = ReplyAvatar)
                    CommentBody(reply, Modifier.padding(start = ReplyIndent))
                }
            }
            VoteButtons(reply, onVote = { value -> actions.onVote(reply, value) }, modifier = Modifier.padding(start = ReplyIndent))
        }
    }
}

@Composable
private fun rowSwipeActions(
    canDelete: Boolean,
    canReport: Boolean,
    onDelete: () -> Unit,
    onReport: () -> Unit,
): List<SwipeAction> {
    val colors = DrokpoTheme.colors
    return buildList {
        if (canDelete) add(SwipeAction("Delete", colors.destructive, onDelete))
        if (canReport) add(SwipeAction("Report", colors.orange, onReport))
    }
}

@Immutable
private data class MenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

private fun longPressItems(
    canDelete: Boolean,
    canReport: Boolean,
    onDelete: () -> Unit,
    onSafety: () -> Unit,
): List<MenuItem> = buildList {
    if (canReport) add(MenuItem("Report or block…", Icons.Outlined.Feedback, onSafety))
    if (canDelete) add(MenuItem("Delete", Icons.Outlined.Delete, onDelete))
}

/**
 * iOS `.contextMenu` on a comment's header + body: a long press opens a menu
 * with "Report or block…" (others' comments) and "Delete" (yours, or any on
 * your community's post — the swipe's action, offered here too because a
 * swipe is easy to miss on Android).
 */
@Composable
private fun LongPressMenuArea(
    id: String,
    items: List<MenuItem>,
    rowUi: RowUiState,
    content: @Composable () -> Unit,
) {
    if (items.isEmpty()) {
        content()
        return
    }
    val colors = DrokpoTheme.colors
    val density = LocalDensity.current
    // The menu opens where the finger was (DropdownMenu offsets are measured from the anchor's bottom-start).
    var pressPosition by remember { mutableStateOf(Offset.Zero) }
    var anchorHeight by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { anchorHeight = it.height }
            .pointerInput(id) {
                detectTapGestures(
                    onLongPress = { position ->
                        pressPosition = position
                        rowUi.onMenuChange(id)
                    },
                )
            }
            .semantics {
                onLongClick(label = "Comment actions") {
                    pressPosition = Offset.Zero
                    rowUi.onMenuChange(id)
                    true
                }
            },
    ) {
        content()
        DropdownMenu(
            expanded = rowUi.menuId == id,
            onDismissRequest = { rowUi.onMenuChange(null) },
            offset = with(density) {
                DpOffset(pressPosition.x.toDp(), (pressPosition.y - anchorHeight).coerceAtMost(0f).toDp())
            },
            containerColor = colors.secondaryGroupedBackground,
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, style = DrokpoTheme.typography.body) },
                    trailingIcon = { Icon(item.icon, contentDescription = null) },
                    onClick = {
                        rowUi.onMenuChange(null)
                        item.onClick()
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = colors.destructive,
                        trailingIconColor = colors.destructive,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun CommentHeader(comment: CommentCard, modifier: Modifier = Modifier, avatarSize: Dp = 32.dp) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RemotePhotoView(
            photo = comment.authorPhoto,
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(comment.authorName ?: "—", style = typography.subheadline.bold(), color = colors.label)
                if (comment.isCommunityAuthor) {
                    Icon(
                        Icons.Filled.Verified,
                        contentDescription = "Community",
                        tint = colors.accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            comment.relativeCreated?.let { relative ->
                Text(relative, style = typography.caption2, color = colors.secondaryLabel)
            }
        }
    }
}

@Composable
internal fun CommentBody(comment: CommentCard, modifier: Modifier = Modifier) {
    val text = comment.text
    val audioUrl = comment.audioUrl?.takeIf { it.isNotBlank() }
    when {
        !text.isNullOrEmpty() -> Text(text, style = DrokpoTheme.typography.subheadline, color = DrokpoTheme.colors.label, modifier = modifier)
        audioUrl != null -> AudioBubbleView(
            id = comment.commentId,
            url = audioUrl,
            durationSec = comment.audioDurationSec ?: 0,
            modifier = modifier,
        )
    }
}

/** Thumbs up / down; the active vote is filled and tinted, counts show when > 0. */
@Composable
private fun VoteButtons(comment: CommentCard, onVote: (String?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        VoteButton(
            label = "Like",
            active = comment.myVote == VOTE_LIKE,
            icon = Icons.Outlined.ThumbUp,
            activeIcon = Icons.Filled.ThumbUp,
            count = comment.likeCount ?: 0,
            onClick = { onVote(nextVote(comment.myVote, VOTE_LIKE)) },
        )
        VoteButton(
            label = "Dislike",
            active = comment.myVote == VOTE_DISLIKE,
            icon = Icons.Outlined.ThumbDown,
            activeIcon = Icons.Filled.ThumbDown,
            count = comment.dislikeCount ?: 0,
            onClick = { onVote(nextVote(comment.myVote, VOTE_DISLIKE)) },
        )
    }
}

@Composable
private fun VoteButton(
    label: String,
    active: Boolean,
    icon: ImageVector,
    activeIcon: ImageVector,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = DrokpoTheme.colors
    val tint = if (active) colors.accent else colors.secondaryLabel
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = if (count > 0) "$label, $count" else label
                selected = active
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (active) activeIcon else icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        if (count > 0) {
            Text("$count", style = DrokpoTheme.typography.caption2, color = tint)
        }
    }
}

/** iOS `Button(…).font(.caption.bold()).foregroundStyle(.secondary)` with `.buttonStyle(.plain)`. */
@Composable
private fun SmallTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = DrokpoTheme.typography.caption.bold(),
        color = DrokpoTheme.colors.secondaryLabel,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/** The pagination spinner under the last row. */
@Composable
internal fun LoadingMoreRow() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Spinner()
    }
}
