package app.drokpo.android.features.likes

import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.drokpo.android.MainTab
import app.drokpo.android.TabReselectEffect
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.ContentEvents
import app.drokpo.android.core.DrokpoJson
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.features.shared.profiledetail.ProfileDetailContext
import app.drokpo.android.features.shared.profiledetail.ProfileDetailScreen
import app.drokpo.android.navigation.backOrClose
import app.drokpo.android.navigation.navIconFor
import app.drokpo.android.navigation.openCommunity
import app.drokpo.android.navigation.openProfile
import app.drokpo.android.navigation.sharedDestinations
import app.drokpo.android.ui.components.ActionRole
import app.drokpo.android.ui.components.AlertButton
import app.drokpo.android.ui.components.DrokpoAlert
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.rememberInAppBrowser
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** The Likes tab's own routes (CONTRACT §C.2); the shared profile/community routes come on top. */
@Serializable
internal sealed interface LikesRoute {
    @Serializable
    data object Home : LikesRoute

    /** A person who liked you: ProfileDetailScreen with "Like back". cardJson = DrokpoJson FeedCard. */
    @Serializable
    data class LikedYouProfile(val cardJson: String) : LikesRoute

    /** A saved community post (read-only). postJson = DrokpoJson CommunityPostCard. */
    @Serializable
    data class SavedPost(val postJson: String) : LikesRoute
}

/**
 * Port of LikesView (Likes tab root). Owns its NavHost (§C.3): the "You liked | Liked you"
 * lists at [LikesRoute.Home], the Like-back profile, the saved-post detail and the shared
 * profile / community / members destinations.
 *
 * [LikesModel] is created here, outside the NavHost — the tab's session scope (§D.3) — so the
 * pushed "Liked you" profile reaches the same model for its "Like back", and the alerts below
 * can show over whichever destination is on top (iOS attaches them to the NavigationStack).
 */
@Composable
fun LikesScreen(modifier: Modifier = Modifier) {
    val model: LikesModel = viewModel { LikesModel() }
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val receivedListState = rememberLazyListState()
    val givenListState = rememberLazyListState()
    val topBarState = rememberTopAppBarState()
    val scope = rememberCoroutineScope()

    // §C.4 step 4: MainTabs flags a "like" push; land on "Liked you" (also checked on appear).
    val focusLikedYou by AppGraph.deepLinks.focusLikedYou.collectAsStateWithLifecycle()
    LaunchedEffect(focusLikedYou) {
        if (focusLikedYou) model.consumeLikePush()
    }

    // Tapping the selected Likes tab pops to the lists (iOS TabView). At the lists it scrolls to
    // the top and brings the large title back. A programmatic scroll skips nested scroll, so
    // the title has to be expanded separately.
    TabReselectEffect(MainTab.Likes) {
        if (nav.previousBackStackEntry != null) {
            nav.popBackStack(nav.graph.findStartDestination().id, inclusive = false)
        } else {
            val list: LazyListState =
                if (model.state.value.direction == Direction.Received) receivedListState else givenListState
            scope.launch { list.animateScrollToItem(0) }
            scope.launch { topBarState.animateExpand() }
        }
    }

    NavHost(nav, startDestination = LikesRoute.Home, modifier = modifier) {
        composable<LikesRoute.Home> { entry ->
            // iOS `.onAppear`: on first show, after a pop back and on every tab switch.
            LaunchedEffect(Unit) { model.onAppear() }
            val openUrl = rememberInAppBrowser()
            val actions = remember(model, nav, entry, openUrl) {
                homeActions(model, nav, entry, openUrl)
            }
            LikesContent(
                state = state,
                actions = actions,
                receivedListState = receivedListState,
                givenListState = givenListState,
                topBarState = topBarState,
            )
        }
        composable<LikesRoute.LikedYouProfile> { entry ->
            val cardJson = entry.toRoute<LikesRoute.LikedYouProfile>().cardJson
            val card = remember(cardJson) { DrokpoJson.decodeFromString(FeedCard.serializer(), cardJson) }
            // ProfileDetailScreen shows its own match alert; likeBack only removes the row.
            val context = remember(model, card) {
                ProfileDetailContext.LikedYou(onLikeBack = { model.likeBack(card) })
            }
            ProfileDetailScreen(
                card = card,
                onBack = { nav.backOrClose(null) },
                context = context,
                navIcon = nav.navIconFor(entry, null),
            )
        }
        composable<LikesRoute.SavedPost> { entry ->
            val postJson = entry.toRoute<LikesRoute.SavedPost>().postJson
            val post = remember(postJson) { DrokpoJson.decodeFromString(CommunityPostCard.serializer(), postJson) }
            LikedPostDetailScreen(
                post = post,
                onBack = { nav.backOrClose(null) },
                onOpenCommunity = { cid -> entry.ifResumed { nav.openCommunity(cid) } },
                navIcon = nav.navIconFor(entry, null),
            )
        }
        sharedDestinations(nav)
    }

    LikesAlerts(
        matched = state.matched,
        errorMessage = state.errorMessage,
        onSayHi = model::sayHi,
        onDismissMatch = model::dismissMatch,
        onDismissError = model::dismissError,
    )
}

private fun homeActions(
    model: LikesModel,
    nav: NavHostController,
    entry: NavBackStackEntry,
    openUrl: (String) -> Unit,
    browserTaps: RepeatTapGuard = RepeatTapGuard(),
): LikesActions = LikesActions(
    onSelectDirection = model::selectDirection,
    onSelectFilter = model::selectFilter,
    onRefresh = { model.refresh() },
    onOpenReceived = { card ->
        entry.ifResumed {
            if (card.isCommunity) {
                nav.openCommunity(card.uid)
            } else {
                nav.navigate(LikesRoute.LikedYouProfile(DrokpoJson.encodeToString(FeedCard.serializer(), card)))
            }
        }
    },
    onLikeBack = model::likeBackFromRow,
    onOpenPerson = { card -> entry.ifResumed { nav.openProfile(card) } },
    onOpenNews = { item ->
        item.url?.let { url ->
            entry.ifResumed {
                browserTaps.run {
                    openUrl(url.toString())
                    ContentEvents.click("news/${item.newsId}")
                }
            }
        }
    },
    onOpenPost = { post ->
        entry.ifResumed {
            nav.navigate(LikesRoute.SavedPost(DrokpoJson.encodeToString(CommunityPostCard.serializer(), post)))
        }
    },
    onRemoveNews = model::unlikeNews,
    onRemovePost = model::unlikePost,
)

/**
 * Navigate only from the destination that's actually on top: a fast double tap would
 * otherwise push the same screen twice (an iOS NavigationLink fires once).
 */
private inline fun NavBackStackEntry.ifResumed(block: () -> Unit) {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block()
}

/**
 * Lets one in-app-browser open through per burst of taps. The screen stays RESUMED for a
 * moment after a Custom Tab launches, so a fast double tap would otherwise open two tabs and
 * report two clicks. iOS `.sheet(item:)` presents once. Time-boxed rather than reset on resume,
 * so a launch that never pauses the screen (no browser installed) can't jam it.
 */
internal class RepeatTapGuard(
    private val windowMillis: Long = 1_000L,
    private val now: () -> Long = SystemClock::uptimeMillis,
) {
    private var lastRun: Long? = null

    fun run(block: () -> Unit) {
        val time = now()
        val last = lastRun
        if (last != null && time - last < windowMillis) return
        lastRun = time
        block()
    }
}

/**
 * The row heart's "It's a match!" (Say hi / Later) and the "Something went wrong" alert.
 * [onSayHi] gets the match id captured when the alert showed — the alert clears
 * [LikesUiState.matched] before running a button.
 */
@Composable
internal fun LikesAlerts(
    matched: MatchedAlert?,
    errorMessage: String?,
    onSayHi: (matchId: String?) -> Unit,
    onDismissMatch: () -> Unit,
    onDismissError: () -> Unit,
) {
    if (matched != null) {
        DrokpoAlert(
            title = "It's a match!",
            message = "You and ${matched.name} liked each other.",
            onDismissRequest = onDismissMatch,
            confirmButton = AlertButton("Say hi") { onSayHi(matched.matchId) },
            dismissButton = AlertButton("Later", ActionRole.Cancel),
        )
    }
    ErrorAlert(message = errorMessage, onDismiss = onDismissError)
}
