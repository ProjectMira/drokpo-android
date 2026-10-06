package app.drokpo.android

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.rememberNavController
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.SessionState
import app.drokpo.android.features.chats.ChatStore
import app.drokpo.android.features.chats.ChatsScreen
import app.drokpo.android.features.chats.LocalChatStore
import app.drokpo.android.features.communityhome.CommunityProfileEditorScreen
import app.drokpo.android.features.feed.FeedScreen
import app.drokpo.android.features.likes.LikesScreen
import app.drokpo.android.features.profile.ProfileScreen
import app.drokpo.android.features.shared.sharing.ShareDestination
import app.drokpo.android.features.shared.sharing.ShareDestinationSheet
import app.drokpo.android.navigation.SharedNavHost
import app.drokpo.android.navigation.SharedRoute
import app.drokpo.android.ui.components.TabCountBadge
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.medium
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/** The five tabs, in iOS order (MainTabView.Tab). */
enum class MainTab { Discover, Likes, Communities, Chats, Profile }

/**
 * Port of MainTabView: the root tab shell for both account types (CONTRACT §A.14, §C.1, §F.0).
 *
 * Person and community accounts share the same tabs — Discover, Likes,
 * Communities, Chats, Profile — so a community can match/chat as itself; the
 * difference is which tab shows for Communities (a person has none — see
 * [visibleTabs]) and Profile (their own community editor vs. a person's
 * dating profile).
 *
 * Composed inside RootScreen's ScopedViewModels for the Main branch — the
 * session scope (§D.3). Only the selected tab is composed, inside a
 * SaveableStateHolder keyed by tab, so every tab's rememberSaveable state and
 * NavHost back stack (and the session-scoped ViewModels behind them) survive
 * tab switches; re-entering a tab re-runs its LaunchedEffects, like iOS
 * `.onAppear` on a tab switch.
 */
@Composable
fun MainTabs() {
    val session = AppGraph.session
    val router = AppGraph.deepLinks

    val state by session.state.collectAsStateWithLifecycle()
    val myCommunity by session.myCommunity.collectAsStateWithLifecycle()
    // RootScreen's Crossfade keeps this outgoing MainTabs composed for ~300 ms after the session
    // leaves Main (sign-out, ActiveCommunity → Failed). Shape the shell from the last *active*
    // account type and community id, so a community shell doesn't re-render as a person's during
    // that fade (Communities tab gone, FeedScreen and the person ProfileScreen composed while
    // signed out). A real person ↔ community switch still updates immediately.
    val activeState = rememberLastNonNull(
        state.takeIf { it == SessionState.ActivePerson || it == SessionState.ActiveCommunity },
    ) ?: state
    val isCommunity = activeState == SessionState.ActiveCommunity
    // uid changes only together with `state`, so reading it after collecting state is safe (§A.3).
    val uid = session.uid
    val myCid = rememberLastNonNull((myCommunity?.uid ?: uid)?.takeIf { it.isNotEmpty() }) ?: ""

    // iOS `@State private var chats = ChatStore()` + `.environment(chats)`. Session-scoped: it is
    // stopped in onCleared() when RootScreen's Main branch leaves composition (iOS .onDisappear).
    val chats: ChatStore = viewModel()
    val totalUnread by chats.totalUnread.collectAsStateWithLifecycle()
    LaunchedEffect(chats, uid) {
        if (uid != null) chats.start(uid)
    }

    // Android 13+: the one-time POST_NOTIFICATIONS prompt needs an Activity (§A.8).
    val activity = LocalActivity.current
    LaunchedEffect(Unit) {
        AppGraph.push.enable(activity)
    }

    var selection by rememberSaveable { mutableStateOf(MainTab.Discover) }
    val tabs = visibleTabs(isCommunity)
    // A person never has the Communities tab; if the account type flips under a selected
    // Communities tab, fall back to Discover instead of rendering a tab that isn't in the bar.
    val selected = if (selection in tabs) selection else MainTab.Discover

    // Push taps (§C.4). A "match"/"message" push-tap lands on the Chats tab; ChatsScreen consumes
    // the router to decide whether to open the thread, so don't clear it here. A "like" push has no
    // thread to open, so land on Likes (flagging LikesScreen to open its "Liked you" segment) and
    // consume it immediately. Runs on first composition too (cold-start taps wait for MainTabs).
    val pendingMatchId by router.pendingMatchId.collectAsStateWithLifecycle()
    val pendingType by router.pendingType.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMatchId, pendingType) {
        if (pendingMatchId == null && pendingType == null) return@LaunchedEffect
        if (pendingType == "like") {
            selection = MainTab.Likes
            router.focusLikedYou.value = true
            router.clear()
        } else {
            selection = MainTab.Chats
        }
    }

    // A shared-content link (chat-bubble tap, drokpo://s/… or https://…/s/… intent): copy it into
    // local state, clear the router, and present ShareDestinationSheet.
    val pendingShare by router.pendingShare.collectAsStateWithLifecycle()
    var sharedDestination by remember { mutableStateOf<ShareDestination?>(null) }
    LaunchedEffect(pendingShare) {
        val destination = pendingShare ?: return@LaunchedEffect
        router.pendingShare.value = null
        sharedDestination = destination
    }

    // Tapping the already-selected tab pops that tab's NavHost to its root (iOS TabView behaviour).
    val reselects = remember { MutableSharedFlow<MainTab>(extraBufferCapacity = 1) }
    val tabStates = rememberSaveableStateHolder()

    CompositionLocalProvider(
        LocalChatStore provides chats,
        LocalTabReselects provides reselects,
    ) {
        MainTabsScaffold(
            tabs = tabs,
            selected = selected,
            chatsUnread = totalUnread,
            onSelect = { tab ->
                if (tab == selected) reselects.tryEmit(tab) else selection = tab
            },
        ) { tab ->
            // The Profile tab hosts different screens per account type — keep their saved state apart.
            val saveKey = if (tab == MainTab.Profile && isCommunity) "Profile.community" else tab.name
            tabStates.SaveableStateProvider(saveKey) {
                when (tab) {
                    MainTab.Discover -> FeedScreen()
                    MainTab.Likes -> LikesScreen()
                    MainTab.Communities -> key(myCid) { CommunityTabHost(myCid) }
                    MainTab.Chats -> ChatsScreen()
                    MainTab.Profile -> if (isCommunity) CommunityProfileEditorScreen() else ProfileScreen()
                }
            }
        }

        sharedDestination?.let { destination ->
            // A new link while one is showing replaces it (iOS `.sheet(item:)` swaps content).
            key(destination.id) {
                ShareDestinationSheet(destination, onDismissRequest = { sharedDestination = null })
            }
        }
    }
}

/**
 * Runs [onReselect] when the user taps the already-selected [tab] (iOS: tapping the active tab pops
 * its NavigationStack to root). Tab roots that own a NavHost pop to their start destination:
 * `TabReselectEffect(MainTab.Likes) { nav.popBackStack(nav.graph.findStartDestination().id, false) }`.
 * A no-op outside MainTabs (e.g. in the debug catalog).
 */
@Composable
fun TabReselectEffect(tab: MainTab, onReselect: () -> Unit) {
    val reselects = LocalTabReselects.current
    val currentOnReselect by rememberUpdatedState(onReselect)
    LaunchedEffect(reselects, tab) {
        reselects.collect { if (it == tab) currentOnReselect() }
    }
}

/**
 * [value], or the last non-null value it had in this composition while it is null. A plain
 * holder, not snapshot state: it is written only from the inputs of the composition that reads it,
 * and nothing needs to recompose when it changes.
 */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val last = remember { LastValue<T>() }
    if (value != null) last.value = value
    return last.value
}

private class LastValue<T : Any> {
    var value: T? = null
}

/** Reselect events from the tab bar; spine-owned plumbing behind [TabReselectEffect] (§D.1). */
private val LocalTabReselects: ProvidableCompositionLocal<Flow<MainTab>> = staticCompositionLocalOf { emptyFlow() }

/**
 * Persons browse communities from a button on the Discover deck instead of a root tab — this
 * tab exists only for a community account's own page (create posts, see the Instagram-style grid).
 */
internal fun visibleTabs(isCommunity: Boolean): List<MainTab> =
    if (isCommunity) MainTab.entries else MainTab.entries - MainTab.Communities

internal val MainTab.label: String
    get() = when (this) {
        MainTab.Discover -> "Discover"
        MainTab.Likes -> "Likes"
        MainTab.Communities -> "Communities"
        MainTab.Chats -> "Chats"
        MainTab.Profile -> "Profile"
    }

/** rectangle.stack.fill / heart.fill / person.3.fill / bubble.left.and.bubble.right.fill / person.fill. */
internal val MainTab.icon: ImageVector
    get() = when (this) {
        MainTab.Discover -> Icons.Filled.ViewCarousel
        MainTab.Likes -> Icons.Filled.Favorite
        MainTab.Communities -> Icons.Filled.Groups
        MainTab.Chats -> Icons.Filled.Forum
        MainTab.Profile -> Icons.Filled.Person
    }

/**
 * The community account's Communities tab: iOS `NavigationStack { CommunityPageView(cid: myCid,
 * ownerMode: true) }` = `SharedNavHost(SharedRoute.Community(myCid, ownerMode = true))`. The tab
 * hoists the NavController so a tab reselect can pop back to the page (§C.2).
 */
@Composable
private fun CommunityTabHost(myCid: String) {
    val nav = rememberNavController()
    SharedNavHost(start = SharedRoute.Community(myCid, ownerMode = true), navController = nav)
    TabReselectEffect(MainTab.Communities) {
        nav.popBackStack(nav.graph.findStartDestination().id, inclusive = false)
    }
}

/**
 * Stateless tab chrome (also rendered by the debug catalog): `Scaffold(bottomBar = NavigationBar,
 * contentWindowInsets = 0)`; the content box consumes only the bar's insets, so each tab root's own
 * Scaffold keeps the status-bar inset (§C.1).
 */
@Composable
internal fun MainTabsScaffold(
    tabs: List<MainTab>,
    selected: MainTab,
    chatsUnread: Int,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (MainTab) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = { MainTabBar(tabs, selected, chatsUnread, onSelect) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            content(selected)
        }
    }
}

/**
 * iOS tab bar: hairline on top, bar material, accent tint for the selected item and grey for the
 * rest — no Material indicator pill. Chats carries `.badge(chats.totalUnread)` (hidden at 0).
 */
@Composable
internal fun MainTabBar(
    tabs: List<MainTab>,
    selected: MainTab,
    chatsUnread: Int,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = colors.accent,
        selectedTextColor = colors.accent,
        indicatorColor = Color.Transparent,
        unselectedIconColor = colors.systemGray,
        unselectedTextColor = colors.systemGray,
    )
    Column(modifier) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.separator)
        NavigationBar(containerColor = colors.bar, tonalElevation = 0.dp) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        if (tab == MainTab.Chats) {
                            BadgedBox(badge = { TabCountBadge(chatsUnread) }) {
                                Icon(tab.icon, contentDescription = null)
                            }
                        } else {
                            Icon(tab.icon, contentDescription = null)
                        }
                    },
                    label = { Text(tab.label, style = DrokpoTheme.typography.caption2.medium(), maxLines = 1) },
                    colors = itemColors,
                )
            }
        }
    }
}
