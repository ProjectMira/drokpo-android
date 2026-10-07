package app.drokpo.android.features.chats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.drokpo.android.MainTab
import app.drokpo.android.TabReselectEffect
import app.drokpo.android.core.AppGraph
import app.drokpo.android.navigation.backOrClose
import app.drokpo.android.navigation.openProfile
import app.drokpo.android.navigation.sharedDestinations
import app.drokpo.android.ui.components.Avatar
import app.drokpo.android.ui.components.CountBadge
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.EmptyState
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.GroupedList
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.Serializable

/** The Chats tab's NavHost routes (CONTRACT §C.2). */
@Serializable
internal sealed interface ChatsRoute {
    @Serializable
    data object List : ChatsRoute

    @Serializable
    data class Thread(val matchId: String) : ChatsRoute
}

/**
 * Port of ChatsView (Chats tab root). Owns its NavHost (§C.4) — the list, threads, and the shared
 * profile / community / members destinations a thread's avatar pushes — and consumes push deep
 * links: a "message" push opens the thread, a "match" push just lands on the list.
 */
@Composable
fun ChatsScreen(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val router = AppGraph.deepLinks
    val pendingMatchId by router.pendingMatchId.collectAsStateWithLifecycle()
    val pendingType by router.pendingType.collectAsStateWithLifecycle()

    NavHost(nav, startDestination = ChatsRoute.List, modifier = modifier) {
        // Pushes only from the resumed (settled, on-top) entry: the list stays tappable during the
        // push transition, so a quick double tap would otherwise stack the thread twice.
        composable<ChatsRoute.List> { entry ->
            ChatsListScreen(
                onOpenThread = { matchId -> if (entry.isResumed()) nav.navigate(ChatsRoute.Thread(matchId)) },
            )
        }
        composable<ChatsRoute.Thread> { entry ->
            ChatThreadScreen(
                matchId = entry.toRoute<ChatsRoute.Thread>().matchId,
                onBack = { nav.backOrClose(null) },
                onOpenProfile = { card -> if (entry.isResumed()) nav.openProfile(card) },
            )
        }
        sharedDestinations(nav)
    }

    // Pushing before entries load is fine — ChatThreadScreen looks the match up by id once the
    // ChatStore listener delivers it. The router is cleared even for a "match" push.
    LaunchedEffect(pendingMatchId, pendingType) {
        val link = chatsDeepLink(pendingType, pendingMatchId) ?: return@LaunchedEffect
        link.openThread?.let { nav.showOnlyThread(it) }
        router.clear()
    }

    TabReselectEffect(MainTab.Chats) {
        nav.popBackStack(nav.graph.findStartDestination().id, inclusive = false)
    }
}

/** iOS `path = [matchId]`: the stack becomes list → that thread, whatever was pushed before. */
private fun NavHostController.showOnlyThread(matchId: String) {
    val top = currentBackStackEntry
    val topIsThread = top?.destination?.hasRoute<ChatsRoute.Thread>() == true
    val alreadyShowing = isThreadOnTopOfList(
        topIsThread = topIsThread,
        topMatchId = if (topIsThread) top.toRoute<ChatsRoute.Thread>().matchId else null,
        previousIsList = previousBackStackEntry?.destination?.hasRoute<ChatsRoute.List>() == true,
        matchId = matchId,
    )
    if (alreadyShowing) return
    navigate(ChatsRoute.Thread(matchId)) {
        popUpTo<ChatsRoute.List> { inclusive = false }
    }
}

private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

/** The list destination: reads the session-scoped ChatStore. */
@Composable
private fun ChatsListScreen(onOpenThread: (String) -> Unit) {
    val chats = LocalChatStore.current
    val isLoading by chats.isLoading.collectAsStateWithLifecycle()
    val newMatches by chats.newMatches.collectAsStateWithLifecycle()
    val conversations by chats.conversations.collectAsStateWithLifecycle()
    val unmatching by chats.unmatching.collectAsStateWithLifecycle()
    val errorMessage by chats.errorMessage.collectAsStateWithLifecycle()
    ChatsContent(
        state = ChatsListState(
            isLoading = isLoading,
            newMatches = newMatches,
            conversations = conversations,
            unmatching = unmatching,
            myUid = AppGraph.session.uid,
            errorMessage = errorMessage,
        ),
        onOpenThread = onOpenThread,
        onUnmatch = { entry -> chats.unmatch(entry.matchId).await() },
        onDismissError = { chats.setError(null) },
    )
}

/** Everything ChatsContent renders. */
internal data class ChatsListState(
    val isLoading: Boolean,
    val newMatches: List<ChatStore.Entry>,
    val conversations: List<ChatStore.Entry>,
    val myUid: String?,
    val unmatching: Set<String> = emptySet(),
    val errorMessage: String? = null,
) {
    /** iOS `chats.entries.isEmpty`. */
    val isEmpty: Boolean get() = newMatches.isEmpty() && conversations.isEmpty()
}

/**
 * Stateless Chats list: large "Chats" title; a spinner while the first snapshot (and profile join)
 * is pending; the empty state; or an inset-grouped list with the "New matches" strip and the
 * conversation rows (swipe "Unmatch"). [onUnmatch] returns whether the unmatch went through; a
 * false brings the swiped row back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatsContent(
    state: ChatsListState,
    onOpenThread: (String) -> Unit,
    onUnmatch: suspend (ChatStore.Entry) -> Boolean,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    /** Catalog only: show this conversation with its swipe action revealed. */
    revealedMatchId: String? = null,
) {
    val colors = DrokpoTheme.colors
    // The inset-grouped list sits on the grouped background; the spinner and empty state on the
    // plain one.
    val background = if (state.isEmpty) colors.background else colors.groupedBackground
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            DrokpoTopBar(
                title = "Chats",
                large = true,
                containerColor = background,
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = background,
    ) { padding ->
        when {
            state.isLoading && state.isEmpty -> LoadingState(Modifier.padding(padding))
            state.isEmpty -> EmptyState(
                icon = Icons.Outlined.Forum,
                title = "No chats yet",
                message = "When you and someone else like each other, you can start chatting here.",
                compact = true,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
            else -> ChatList(
                state = state,
                padding = padding,
                onOpenThread = onOpenThread,
                onUnmatch = onUnmatch,
                revealedMatchId = revealedMatchId,
            )
        }
    }
    ErrorAlert(state.errorMessage, onDismiss = onDismissError)
}

@Composable
private fun ChatList(
    state: ChatsListState,
    padding: PaddingValues,
    onOpenThread: (String) -> Unit,
    onUnmatch: suspend (ChatStore.Entry) -> Boolean,
    revealedMatchId: String?,
) {
    val layoutDirection = LocalLayoutDirection.current
    val listState = rememberLazyListState()
    // The conversation whose Unmatch button is showing. At most one, as in a UITableView: opening
    // another row, scrolling, or tapping elsewhere in the list closes it.
    var openId by remember { mutableStateOf(revealedMatchId) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { it }.collect { openId = null }
    }
    // A tap while a row is open only closes it (UITableView). Ignores an open id whose row is gone.
    val openRowShowing = openId?.let { id -> id !in state.unmatching && state.conversations.any { it.matchId == id } } == true
    val openThread: (String) -> Unit = { matchId ->
        if (openRowShowing) openId = null else onOpenThread(matchId)
    }
    GroupedList(
        state = listState,
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection),
            end = padding.calculateEndPadding(layoutDirection),
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        if (state.newMatches.isNotEmpty()) {
            item(key = "new-matches", contentType = "new-matches") {
                NewMatchesSection(state.newMatches, openThread)
            }
        }
        if (state.conversations.isNotEmpty()) {
            item(key = "conversations", contentType = "conversations") {
                // Separators line up with the row text, past the 56dp avatar (16 + 56 + 12).
                GroupedSection(separatorInset = 84.dp) {
                    state.conversations.forEach { entry ->
                        key(entry.matchId) {
                            // The swipe "Unmatch" hides the row at once; the listener then drops
                            // the match. A failed request brings it back, slid closed.
                            AnimatedVisibility(
                                visible = entry.matchId !in state.unmatching,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                SwipeActionRow(
                                    actionLabel = "Unmatch",
                                    onAction = { onUnmatch(entry) },
                                    onClick = { openThread(entry.matchId) },
                                    isOpen = entry.matchId == openId,
                                    onOpenChange = { open ->
                                        if (open) {
                                            openId = entry.matchId
                                        } else if (openId == entry.matchId) {
                                            openId = null
                                        }
                                    },
                                ) {
                                    ConversationRow(entry, state.myUid)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Section "New matches": a horizontal strip of 64dp avatars with the name under each. */
@Composable
private fun NewMatchesSection(newMatches: List<ChatStore.Entry>, onOpenThread: (String) -> Unit) {
    val colors = DrokpoTheme.colors
    GroupedSection(header = "New matches") {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(newMatches, key = { it.matchId }) { entry ->
                val name = entry.otherUser?.displayName
                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenThread(entry.matchId) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Avatar(photo = entry.otherUser?.photos?.firstOrNull(), name = name, size = 64.dp)
                    Text(
                        name ?: "—",
                        style = DrokpoTheme.typography.caption,
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Conversation row: 56dp avatar, name (headline), last message (one line, "You: " when it's mine),
 * unread count pill when > 0, and the NavigationLink disclosure chevron.
 */
@Composable
private fun ConversationRow(entry: ChatStore.Entry, myUid: String?) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    val name = entry.otherUser?.displayName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(photo = entry.otherUser?.photos?.firstOrNull(), name = name, size = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name ?: "—", style = typography.headline, color = colors.label)
            previewText(entry, myUid)?.let { preview ->
                Text(
                    preview,
                    style = typography.subheadline,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.unread > 0) {
            CountBadge(entry.unread)
        }
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = colors.tertiaryLabel,
            modifier = Modifier.size(14.dp),
        )
    }
}
