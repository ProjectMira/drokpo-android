package app.drokpo.android.features.likes

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.LikedContent
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.ui.components.ChipBar
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.EmptyState
import app.drokpo.android.ui.components.ListDivider
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.SegmentedPicker
import app.drokpo.android.ui.components.TintedTag
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Everything the Likes root can ask its host to do. Defaults are no-ops (debug catalog). */
@Immutable
internal data class LikesActions(
    val onSelectDirection: (Direction) -> Unit = {},
    val onSelectFilter: (GivenFilter) -> Unit = {},
    val onRefresh: () -> Unit = {},
    /** A "Liked you" row: a community opens its page, anyone else the Like-back profile. */
    val onOpenReceived: (FeedCard) -> Unit = {},
    /** The heart on a "Liked you" row. */
    val onLikeBack: (FeedCard) -> Unit = {},
    /** A person (or community account) you liked: their profile / page. */
    val onOpenPerson: (FeedCard) -> Unit = {},
    val onOpenNews: (NewsCard) -> Unit = {},
    val onOpenPost: (CommunityPostCard) -> Unit = {},
    /** "Remove" on a saved story; true once it's gone (false → a swiped row slides back). */
    val onRemoveNews: suspend (NewsCard) -> Boolean = { false },
    val onRemovePost: suspend (CommunityPostCard) -> Boolean = { false },
)

/**
 * Port of LikesView's body (stateless): large "Likes" title, the "You liked | Liked you"
 * segmented control, the "You liked" filter pills, then the spinner, the segment's empty
 * state or its plain list. Pull to refresh reloads silently.
 */
@Composable
internal fun LikesContent(
    state: LikesUiState,
    actions: LikesActions,
    modifier: Modifier = Modifier,
    receivedListState: LazyListState = rememberLazyListState(),
    givenListState: LazyListState = rememberLazyListState(),
    /** The large title's collapse state; hoisted so a tab reselect can bring the title back. */
    topBarState: TopAppBarState = rememberTopAppBarState(),
) {
    val colors = DrokpoTheme.colors
    // The list on screen, or null for the spinner and the empty states.
    val activeList: LazyListState? = when {
        state.isLoading -> null
        state.direction == Direction.Received -> receivedListState.takeIf { state.received.isNotEmpty() }
        else -> givenListState.takeIf { state.givenEntries.isNotEmpty() }
    }
    val currentActiveList by rememberUpdatedState(activeList)
    // Only content that can actually scroll collapses the title. An empty state or a short list
    // keeps it large, as on iOS where the title springs back once the drag ends.
    val canScroll = remember { { currentActiveList?.let { it.canScrollForward || it.canScrollBackward } == true } }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = topBarState, canScroll = canScroll)
    // Bring the large title back when the switch lands on content that can't collapse it (spinner,
    // empty state) or on a list that's at its top. Otherwise canScroll would leave it stuck collapsed.
    LaunchedEffect(activeList, topBarState) {
        val list = activeList
        if (list == null || (list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0)) {
            topBarState.animateExpand()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = { DrokpoTopBar("Likes", large = true, scrollBehavior = scrollBehavior) },
        containerColor = colors.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            SegmentedPicker(
                options = Direction.entries,
                selected = state.direction,
                onSelect = actions.onSelectDirection,
                label = { it.label },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
            )

            if (state.direction == Direction.Given) {
                ChipBar(
                    options = GivenFilter.entries,
                    selected = state.givenFilter,
                    onSelect = actions.onSelectFilter,
                    label = { it.label },
                )
            }

            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = actions.onRefresh,
                state = pullState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = colors.secondaryGroupedBackground,
                        color = colors.secondaryLabel,
                    )
                },
            ) {
                // The large title collapses with the list. Attached inside the refresh box so
                // a downward pull first re-expands the title, then starts a refresh (iOS order).
                val listModifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                when {
                    state.isLoading -> LoadingState()
                    state.direction == Direction.Received ->
                        if (state.received.isEmpty()) {
                            LikesEmptyState(Direction.Received, listModifier)
                        } else {
                            ReceivedList(state.receivedRows, actions, receivedListState, listModifier)
                        }
                    else ->
                        if (state.givenEntries.isEmpty()) {
                            LikesEmptyState(Direction.Given, listModifier)
                        } else {
                            GivenList(state.givenEntries, actions, givenListState, listModifier)
                        }
                }
            }
        }
    }
}

/** Animates the large title back to fully expanded (tab reselect, segment switch). */
internal suspend fun TopAppBarState.animateExpand() {
    if (heightOffset != 0f) {
        animate(heightOffset, 0f) { value, _ -> heightOffset = value }
    }
    contentOffset = 0f
}

/**
 * The per-segment empty state. A lazy list with one full-height item, so pull to refresh
 * still works with nothing to show.
 */
@Composable
private fun LikesEmptyState(direction: Direction, modifier: Modifier) {
    LazyColumn(modifier) {
        item {
            if (direction == Direction.Received) {
                EmptyState(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "No likes yet",
                    message = "Likes you receive will show up here.",
                    compact = true,
                    modifier = Modifier.fillParentMaxSize(),
                )
            } else {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Send,
                    title = "Nothing saved yet",
                    message = "People, news, and community posts you like in Discover will show up here.",
                    compact = true,
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ReceivedList(
    rows: List<ReceivedRow>,
    actions: LikesActions,
    listState: LazyListState,
    modifier: Modifier,
) {
    LazyColumn(modifier, state = listState) {
        items(rows, key = { it.id }) { row ->
            LikeRow(
                card = row.card,
                showLikeBack = true,
                onClick = { actions.onOpenReceived(row.card) },
                onLikeBack = { actions.onLikeBack(row.card) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun GivenList(
    entries: List<GivenEntry>,
    actions: LikesActions,
    listState: LazyListState,
    modifier: Modifier,
) {
    val openRows = remember { OpenRowTracker() }
    // Scrolling the list puts away an open "Remove" button (iOS List).
    LaunchedEffect(listState, openRows) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (scrolling) openRows.closeAll() }
    }
    LazyColumn(modifier, state = listState) {
        items(entries, key = { it.id }, contentType = ::givenContentType) { entry ->
            val itemModifier = Modifier.animateItem()
            when (entry) {
                is GivenEntry.Person -> entry.entry.otherUser?.let { card ->
                    LikeRow(
                        card = card,
                        showLikeBack = false,
                        onClick = { openRows.tapOr { actions.onOpenPerson(card) } },
                        onLikeBack = {},
                        modifier = itemModifier,
                    )
                }
                is GivenEntry.Content -> when (val content = entry.content) {
                    is LikedContent.News -> RemovableRow(
                        id = entry.id,
                        onRemove = { actions.onRemoveNews(content.item) },
                        modifier = itemModifier,
                        openRows = openRows,
                    ) { removal ->
                        LikedNewsRow(
                            item = content.item,
                            onOpen = { openRows.tapOr { actions.onOpenNews(content.item) } },
                            removal = removal,
                        )
                    }
                    is LikedContent.Post -> RemovableRow(
                        id = entry.id,
                        onRemove = { actions.onRemovePost(content.post) },
                        modifier = itemModifier,
                        openRows = openRows,
                    ) { removal ->
                        LikedPostRow(
                            post = content.post,
                            onClick = { openRows.tapOr { actions.onOpenPost(content.post) } },
                            removal = removal,
                        )
                    }
                }
            }
        }
    }
}

/** Three row layouts share the merged list; LazyColumn only reuses a composition within one. */
private fun givenContentType(entry: GivenEntry): Int = when (entry) {
    is GivenEntry.Person -> 0
    is GivenEntry.Content -> if (entry.content is LikedContent.News) 1 else 2
}

// region Rows

/** Leading edge of a row's text — where iOS insets the row separator. */
private val PhotoRowTextInset = 16.dp + 56.dp + 12.dp
private val NewsRowTextInset = 16.dp + 72.dp + 12.dp
private val PostRowTextInset = 16.dp + 24.dp + 12.dp

/**
 * A single Likes-list row: 56dp circular photo, name (headline) + age, then the "Community"
 * capsule or the region, and on "Liked you" a heart that likes back without opening the
 * profile. iOS shows a person glyph (not initials) when there's no photo.
 */
@Composable
internal fun LikeRow(
    card: FeedCard,
    showLikeBack: Boolean,
    onClick: () -> Unit,
    onLikeBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(modifier.background(colors.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemotePhotoView(
                photo = card.photos?.firstOrNull(),
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        card.displayName ?: "—",
                        style = typography.headline,
                        color = colors.label,
                        modifier = Modifier
                            .alignByBaseline()
                            .weight(1f, fill = false),
                    )
                    card.displayAge?.let { age ->
                        Text(
                            "$age",
                            style = typography.body,
                            color = colors.secondaryLabel,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
                if (card.isCommunity) {
                    TintedTag("Community")
                } else {
                    card.region?.let { region ->
                        Text(region, style = typography.subheadline, color = colors.secondaryLabel)
                    }
                }
            }
            if (showLikeBack) {
                LikeBackHeart(onClick = onLikeBack)
            }
            DisclosureChevron()
        }
        ListDivider(startIndent = PhotoRowTextInset)
    }
}

/** `heart.fill` in brandRed on a `.quaternary` circle (borderless: doesn't open the row). */
@Composable
private fun LikeBackHeart(onClick: () -> Unit) {
    val colors = DrokpoTheme.colors
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(colors.fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Favorite,
            contentDescription = "Like back",
            tint = colors.brandRed,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The NavigationLink disclosure indicator of a plain iOS list row. */
@Composable
private fun DisclosureChevron() {
    Icon(
        Icons.AutoMirrored.Rounded.ArrowForwardIos,
        contentDescription = null,
        tint = DrokpoTheme.colors.tertiaryLabel,
        modifier = Modifier.size(14.dp),
    )
}

/**
 * A saved news story in the "You liked" list: 72×54 thumbnail, uppercased source, two-line
 * title, `arrow.up.right` — tapping opens the source in the in-app browser.
 */
@Composable
internal fun LikedNewsRow(
    item: NewsCard,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    removal: RowRemoval? = null,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(modifier.background(colors.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .removableClickable(onClick = onOpen, removal = removal)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemotePhotoView(
                photo = item.displayPhotos.firstOrNull(),
                modifier = Modifier
                    .size(width = 72.dp, height = 54.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                item.sourceName?.let { sourceName ->
                    Text(sourceName.uppercase(), style = typography.caption2.bold(), color = colors.secondaryLabel)
                }
                Text(
                    item.title ?: "—",
                    style = typography.subheadline.bold(),
                    color = colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.ArrowOutward,
                contentDescription = null,
                tint = colors.secondaryLabel,
                modifier = Modifier.size(14.dp),
            )
        }
        ListDivider(startIndent = NewsRowTextInset)
    }
}

/** A saved community post in the "You liked" list: kind icon, community name, title. */
@Composable
internal fun LikedPostRow(
    post: CommunityPostCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    removal: RowRemoval? = null,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(modifier.background(colors.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .removableClickable(onClick = onClick, removal = removal)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                Icon(likedPostIcon(post.kind), contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                post.communityName?.let { name ->
                    Text(name.uppercase(), style = typography.caption2.bold(), color = colors.secondaryLabel)
                }
                Text(
                    post.title ?: "—",
                    style = typography.subheadline.bold(),
                    color = colors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DisclosureChevron()
        }
        ListDivider(startIndent = PostRowTextInset)
    }
}

/** link / chart.bar.fill / calendar / megaphone.fill. */
internal fun likedPostIcon(kind: String?): ImageVector = when (kind) {
    "link" -> Icons.Filled.Link
    "poll" -> Icons.Filled.BarChart
    "event" -> Icons.Filled.CalendarMonth
    else -> Icons.Filled.Campaign
}

/** How a saved row offers "Remove" besides the swipe: the long-press menu and TalkBack. */
@Immutable
internal class RowRemoval(val onLongPress: () -> Unit, val onRemove: () -> Unit)

/**
 * The row's tap target. With [removal]: long press opens the "Remove" menu, and the same node
 * carries a "Remove" accessibility action (a swipe isn't reachable from TalkBack). No haptics:
 * the iOS app has none (CONTRACT §0.4).
 */
private fun Modifier.removableClickable(onClick: () -> Unit, removal: RowRemoval?): Modifier =
    if (removal == null) {
        clickable(onClick = onClick)
    } else {
        combinedClickable(
            onClickLabel = null,
            onLongClickLabel = "Remove",
            onLongClick = removal.onLongPress,
            hapticFeedbackEnabled = false,
            onClick = onClick,
        ).semantics {
            customActions = listOf(CustomAccessibilityAction("Remove") { removal.onRemove(); true })
        }
    }

/**
 * Which saved row has its "Remove" button out. Like an iOS List there's at most one: opening
 * another row, scrolling or tapping anywhere in the list puts it away.
 */
@Stable
internal class OpenRowTracker(openId: String? = null) {
    var openId: String? by mutableStateOf(openId)

    fun closeAll() {
        openId = null
    }

    /** A tap while a row shows "Remove" only puts the button away (iOS); otherwise runs [action]. */
    fun tapOr(action: () -> Unit) {
        if (openId != null) closeAll() else action()
    }
}

/** Where a released swipe settles. */
internal enum class SwipeTarget { Closed, Revealed, Removed }

/** How far a drag must pull the row (of its width) to remove it without the button (iOS full swipe). */
internal const val FULL_SWIPE_FRACTION = 0.6f

/**
 * Where a row released at [offset] (px, ≤ 0) with [velocity] (px/s, negative = leftward) goes.
 * A flick only ever opens or closes the "Remove" button. Removing outright takes a deliberate
 * drag past [FULL_SWIPE_FRACTION] of the row's [width], so a casual or diagonal flick while
 * scrolling can't unsave anything. Otherwise the row opens once it's past half the button.
 */
internal fun swipeTarget(
    offset: Float,
    velocity: Float,
    width: Float,
    revealWidth: Float,
    flickVelocity: Float,
): SwipeTarget = when {
    velocity >= flickVelocity -> SwipeTarget.Closed
    width > 0f && offset <= -width * FULL_SWIPE_FRACTION -> SwipeTarget.Removed
    velocity <= -flickVelocity -> SwipeTarget.Revealed
    offset <= -revealWidth / 2f -> SwipeTarget.Revealed
    else -> SwipeTarget.Closed
}

/**
 * The horizontal position of a saved row's content: 0 when closed, negative when pulled left.
 * Kept in plain `remember` and never saved, so a row that leaves composition (pill or tab
 * switch, a push, scrolling away) comes back closed. Nothing can replay a swipe from earlier.
 */
@Stable
private class RowSwipeState(initialOffset: Float) {
    var offset by mutableFloatStateOf(initialOffset)
        private set

    /** The row's measured width in px: the furthest a full swipe goes. */
    var width by mutableFloatStateOf(0f)

    val draggable = DraggableState { delta -> offset = (offset + delta).coerceIn(-width, 0f) }

    /**
     * Animates to [target]. The user's finger (the gesture's UserInput priority) pre-empts it.
     * That pre-emption is swallowed here, so callers like a collecting effect keep running.
     */
    suspend fun animateTo(target: Float, initialVelocity: Float = 0f) {
        try {
            draggable.drag(MutatePriority.Default) {
                var previous = offset
                animate(previous, target, initialVelocity, SwipeSpring) { value, _ ->
                    dragBy(value - previous)
                    previous = value
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }
}

private val SwipeSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

/** Width of the revealed "Remove" button (iOS swipe-action button for a one-word label). */
private val RemoveButtonWidth: Dp = 88.dp

/** Release speed that counts as a flick: it opens or closes the button but never removes. */
private val FlickVelocity: Dp = 300.dp

/**
 * iOS `.swipeActions { Button("Remove", role: .destructive) }`. Swiping left reveals a red
 * "Remove" button that the user taps. Only a long, deliberate full swipe removes straight away.
 * The Android long-press menu and the TalkBack action offer the same "Remove". Removing slides
 * the row off (the destructive role's animation) while the DELETE runs. The row slides back if
 * the DELETE fails (the alert explains why), and fades out once the model drops it.
 *
 * [initiallyRevealed] is for the debug catalog only.
 */
@Composable
internal fun RemovableRow(
    id: String,
    onRemove: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    openRows: OpenRowTracker = remember { OpenRowTracker() },
    initiallyRevealed: Boolean = false,
    content: @Composable (removal: RowRemoval) -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { RemoveButtonWidth.toPx() }
    val flickPx = with(density) { FlickVelocity.toPx() }
    val scope = rememberCoroutineScope()
    val currentOnRemove by rememberUpdatedState(onRemove)
    val swipe = remember { RowSwipeState(initialOffset = if (initiallyRevealed) -revealPx else 0f) }
    var removing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val removal = remember(scope, swipe, id, openRows) {
        RowRemoval(
            onLongPress = { menuOpen = true },
            onRemove = {
                if (!removing) {
                    removing = true
                    if (openRows.openId == id) openRows.closeAll()
                    scope.launch {
                        // Slide away at once, like a destructive swipe action, and wait for the DELETE.
                        launch { swipe.animateTo(-swipe.width) }
                        val removed = currentOnRemove()
                        if (!removed) swipe.animateTo(0f)
                        removing = false
                    }
                }
            },
        )
    }

    // Another row opening, a scroll or a tap in the list closes this one. Changes only: the first
    // value is skipped so the catalog's pre-revealed row stays open.
    LaunchedEffect(swipe, openRows, id) {
        snapshotFlow { openRows.openId == id }.drop(1).collect { mine ->
            if (!mine && !removing && swipe.offset != 0f) swipe.animateTo(0f)
        }
    }
    // A row that goes away while open mustn't leave the next tap in the list swallowed.
    DisposableEffect(openRows, id) {
        onDispose { if (openRows.openId == id) openRows.closeAll() }
    }

    val showingButton by remember(swipe) { derivedStateOf { swipe.offset < 0f } }
    Box(
        modifier
            .onSizeChanged { swipe.width = it.width.toFloat() }
            .clipToBounds()
            .draggable(
                state = swipe.draggable,
                orientation = Orientation.Horizontal,
                enabled = !removing,
                onDragStarted = { openRows.openId = id },
                onDragStopped = { velocity ->
                    when (swipeTarget(swipe.offset, velocity, swipe.width, revealPx, flickPx)) {
                        SwipeTarget.Removed -> removal.onRemove()
                        SwipeTarget.Revealed -> {
                            openRows.openId = id
                            swipe.animateTo(-revealPx, velocity)
                        }
                        SwipeTarget.Closed -> {
                            if (openRows.openId == id) openRows.closeAll()
                            swipe.animateTo(0f, velocity)
                        }
                    }
                },
            ),
    ) {
        if (showingButton) {
            RemoveButton(
                onClick = removal.onRemove,
                modifier = Modifier
                    .matchParentSize()
                    .layout { measurable, constraints ->
                        // As wide as the gap the row leaves at the trailing edge (layout phase only,
                        // so dragging doesn't recompose).
                        val gap = (-swipe.offset).roundToInt().coerceIn(0, constraints.maxWidth)
                        val placeable = measurable.measure(Constraints.fixed(gap, constraints.maxHeight))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(constraints.maxWidth - gap, 0)
                        }
                    },
            )
        }
        Box(Modifier.absoluteOffset { IntOffset(swipe.offset.roundToInt(), 0) }) {
            content(removal)
        }
        RemoveMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            onRemove = {
                menuOpen = false
                removal.onRemove()
            },
        )
    }
}

/**
 * The destructive swipe button: system red with a white subheadline-bold "Remove". The label
 * keeps the button's width: it's centred while the button is still opening, then rides the
 * row's trailing edge on a full swipe.
 */
@Composable
private fun RemoveButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clipToBounds()
            .background(DrokpoTheme.colors.destructive)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.requiredWidth(RemoveButtonWidth), contentAlignment = Alignment.Center) {
            Text(
                "Remove",
                style = DrokpoTheme.typography.subheadline.bold(),
                color = Color.White,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** The long-press menu on a saved row: one destructive "Remove". Stateless, for the catalog. */
@Composable
internal fun RemoveMenu(expanded: Boolean, onDismissRequest: () -> Unit, onRemove: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        val destructive = DrokpoTheme.colors.destructive
        DropdownMenuItem(
            text = { Text("Remove") },
            leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null) },
            colors = MenuDefaults.itemColors(textColor = destructive, leadingIconColor = destructive),
            onClick = onRemove,
        )
    }
}

// endregion
