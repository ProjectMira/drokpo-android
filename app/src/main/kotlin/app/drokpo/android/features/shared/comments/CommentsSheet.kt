package app.drokpo.android.features.shared.comments

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.model.CommentCard
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.ui.components.ActionSheet
import app.drokpo.android.ui.components.ActionSheetItem
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.EmptyState
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.ListDivider
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.LocalInsideSheet
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.ScopedViewModels
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Instagram-style comments on a community post: a list of top-level comments
 * (newest first) with one level of lazily-loaded replies, and a composer that
 * accepts text or a voice recording. Self-contained bottom sheet with a
 * half-height first stop (iOS `.presentationDetents([.medium, .large])`);
 * builds a fresh [CommentsModel] per presentation.
 *
 * This is [DrokpoSheet]'s exact configuration (container colours, grabber,
 * [LocalInsideSheet], presentation-scoped ViewModels), hosted here only
 * because the comments sheet needs its [SheetState]: like an iOS sheet making
 * room for the keyboard, it moves to full height as soon as the composer takes
 * focus — at the half-height stop the IME would leave almost nothing visible —
 * and Close animates the sheet away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(post: CommunityPostCard, onDismissRequest: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { currentOnDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = DrokpoTheme.colors.background,
        contentColor = DrokpoTheme.colors.label,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        CompositionLocalProvider(LocalInsideSheet provides true) {
            ScopedViewModels {
                CommentsScreen(
                    post = post,
                    onClose = close,
                    onComposerFocused = {
                        if (sheetState.currentValue == SheetValue.PartiallyExpanded) {
                            scope.launch { sheetState.expand() }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CommentsScreen(post: CommunityPostCard, onClose: () -> Unit, onComposerFocused: () -> Unit) {
    // ScopedViewModels scopes ViewModels to this presentation, so every opening
    // starts a fresh model whose init runs the first load (iOS `.task`).
    val model: CommentsModel = viewModel(key = "comments-${post.postId}") {
        CommentsModel(
            postId = post.postId,
            postOwnerCid = post.communityId,
            myUid = AppGraph.session.uid,
            writeScope = AppGraph.appScope,
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    // Long-pressed / swiped comment awaiting a report/block choice, then (if
    // "Report…" was picked) a reason.
    var safetyTarget by remember { mutableStateOf<CommentCard?>(null) }
    var reportTarget by remember { mutableStateOf<CommentCard?>(null) }
    val actions = remember(model) {
        CommentsActions(
            onRefresh = model::refresh,
            onReachEnd = model::loadMoreIfNeeded,
            onReply = model::setReplyTarget,
            onShowReplies = model::toggleReplies,
            onVote = model::vote,
            onDelete = model::delete,
            onSafety = { safetyTarget = it },
        )
    }

    CommentsContent(
        state = state,
        actions = actions,
        onClose = onClose,
        modifier = Modifier.fillMaxSize(),
    ) { composerModifier ->
        CommentComposer(
            replyingTo = state.replyTarget?.let { it.authorName ?: "member" },
            isSending = state.isSending,
            onCancelReply = model::cancelReply,
            onSend = model::submit,
            onInputFocused = onComposerFocused,
            modifier = composerModifier,
        )
    }

    CommentSafetySheets(
        safetyTarget = safetyTarget,
        reportTarget = reportTarget,
        onDismissSafety = { safetyTarget = null },
        onDismissReport = { reportTarget = null },
        onReport = { reportTarget = it },
        onBlock = model::blockAuthor,
        onReportReason = model::reportAuthor,
    )
    ErrorAlert(message = state.errorMessage, onDismiss = model::dismissError)
}

/**
 * The sheet's body: "Comments" bar with Close, the list (spinner / empty /
 * comments with pull-to-refresh and paging), and [composer] pinned to the
 * bottom of the visible sheet.
 *
 * At the half-height stop a ModalBottomSheet keeps its full height and just
 * hangs below the screen edge, which would hide a composer laid out at the
 * bottom. So the composer is lifted (placement only — the sheet's size never
 * changes, or its anchors would chase it) by however much of this column is
 * below the visible bottom: the screen edge, the navigation bar or the IME.
 * The list can't scroll at that stop anyway (dragging up expands the sheet
 * first), so nothing useful sits behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommentsContent(
    state: CommentsUiState,
    actions: CommentsActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialOpenSwipeId: String? = null,
    initialMenuId: String? = null,
    composer: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val safeDrawing = WindowInsets.safeDrawing
    var listHeight by remember { mutableIntStateOf(0) }
    var composerLift by remember { mutableIntStateOf(0) }
    var openSwipeId by remember { mutableStateOf(initialOpenSwipeId) }
    var menuId by remember { mutableStateOf(initialMenuId) }
    val rowUi = RowUiState(
        openSwipeId = openSwipeId,
        onOpenSwipeChange = { openSwipeId = it },
        menuId = menuId,
        onMenuChange = { menuId = it },
    )

    Column(
        modifier
            .imePadding()
            .onGloballyPositioned { coordinates ->
                val root = coordinates.findRootCoordinates()
                val bottom = root.localPositionOf(coordinates, Offset(0f, coordinates.size.height.toFloat())).y
                val visibleBottom = root.size.height - safeDrawing.getBottom(density)
                composerLift = (bottom - visibleBottom).roundToInt().coerceIn(0, listHeight)
            },
    ) {
        DrokpoTopBar(title = "Comments", navIcon = NavIcon.Close, onNavIcon = onClose)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { listHeight = it.height },
        ) {
            // The spinner and the empty state centre in the part of the list that
            // is actually on screen (above the lifted composer), like iOS laying
            // the sheet out at the .medium detent. Read at placement time, so
            // dragging the sheet doesn't recompose every frame.
            val centreInVisiblePart = Modifier.offset { IntOffset(0, -composerLift / 2) }
            when {
                state.isLoading -> LoadingState(centreInVisiblePart)
                state.comments.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Forum,
                    title = "No comments yet",
                    message = "Be the first to say something.",
                    compact = true,
                    modifier = Modifier.fillMaxSize().then(centreInVisiblePart),
                )
                else -> PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = actions.onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CommentsList(state = state, actions = actions, rowUi = rowUi, onScroll = { openSwipeId = null })
                }
            }
        }
        composer(Modifier.offset { IntOffset(0, -composerLift) })
    }
}

@Composable
private fun CommentsList(
    state: CommentsUiState,
    actions: CommentsActions,
    rowUi: RowUiState,
    onScroll: () -> Unit,
) {
    val listState = rememberLazyListState()
    // iOS closes an open swipe as soon as the list scrolls.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) onScroll()
    }
    // A keyed LazyColumn keeps its old first row pinned when rows are inserted
    // above it, which would leave a just-posted comment (or the newer comments
    // a pull-to-refresh brought in) above the viewport. iOS shows them.
    LaunchedEffect(state.scrollToTopToken) {
        if (state.scrollToTopToken > 0) listState.animateScrollToItem(0)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        itemsIndexed(state.comments, key = { _, comment -> comment.commentId }) { index, comment ->
            if (index == state.comments.lastIndex) {
                LaunchedEffect(comment.commentId) { actions.onReachEnd(comment) }
            }
            CommentRow(comment = comment, state = state, actions = actions, rowUi = rowUi)
            if (index < state.comments.lastIndex) {
                // Plain-list separator, aligned with the author name like iOS.
                ListDivider(startIndent = 56.dp)
            }
        }
        if (state.isLoadingMore) {
            item(key = "loading-more") { LoadingMoreRow() }
        }
    }
}

/**
 * The two chained confirmation dialogs: "Comment by {name}" (Report… / Block
 * {name}), then "Why are you reporting this comment?" with the shared reasons.
 */
@Composable
internal fun CommentSafetySheets(
    safetyTarget: CommentCard?,
    reportTarget: CommentCard?,
    onDismissSafety: () -> Unit,
    onDismissReport: () -> Unit,
    onReport: (CommentCard) -> Unit,
    onBlock: (CommentCard) -> Unit,
    onReportReason: (CommentCard, String) -> Unit,
) {
    if (safetyTarget != null) {
        val name = safetyTarget.authorName ?: "member"
        ActionSheet(
            onDismissRequest = onDismissSafety,
            title = "Comment by $name",
            items = listOf(
                ActionSheetItem("Report…", destructive = true) { onReport(safetyTarget) },
                ActionSheetItem("Block $name", destructive = true) { onBlock(safetyTarget) },
            ),
        )
    }
    if (reportTarget != null) {
        ActionSheet(
            onDismissRequest = onDismissReport,
            title = "Why are you reporting this comment?",
            items = Vocabulary.reportReasons.map { reason ->
                ActionSheetItem(reason, destructive = true) { onReportReason(reportTarget, reason) }
            },
        )
    }
}
