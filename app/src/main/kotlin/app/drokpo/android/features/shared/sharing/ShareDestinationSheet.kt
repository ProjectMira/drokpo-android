package app.drokpo.android.features.shared.sharing

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.drokpo.android.core.ContentEvents
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.features.shared.comments.CommentsSheet
import app.drokpo.android.features.shared.community.CommunityPageScreen
import app.drokpo.android.features.shared.profiledetail.ProfileDetailScreen
import app.drokpo.android.navigation.SharedRoute
import app.drokpo.android.navigation.backOrClose
import app.drokpo.android.navigation.navIconFor
import app.drokpo.android.navigation.openCommunity
import app.drokpo.android.navigation.openCommunityMembers
import app.drokpo.android.navigation.sharedDestinations
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.rememberInAppBrowser
import kotlinx.serialization.Serializable

/**
 * Port of ShareDestinationView: presents content that arrived via a share link
 * (chat bubble tap, drokpo:// scheme, App Link) — fetches the target by id and
 * renders the same detail view the rest of the app uses. Presented by MainTabs.
 *
 * A sheet (iOS `.sheet`, large detent; [ShareModalSheet] = DrokpoSheet's
 * configuration plus an animated close) with its own NavHost (iOS
 * NavigationStack): the start route is the loader for [destination] (a
 * community goes straight to its page), plus [sharedDestinations] so headers
 * and member rows push inside the sheet. The root's leading button is the
 * iOS "Close" (X). (CONTRACT §B.11, §C.2, §F.11.)
 */
@Composable
fun ShareDestinationSheet(destination: ShareDestination, onDismissRequest: () -> Unit) {
    // DrokpoSheet's configuration with an animated close: Close (X) at the root, and the close
    // after a built-in block, slide the sheet down like iOS `dismiss()` (see ShareModalSheet).
    ShareModalSheet(onDismissRequest = onDismissRequest, skipPartiallyExpanded = true) { close ->
        // A different link swaps the whole stack, like iOS `.sheet(item:)` replacing its content.
        key(destination.id) {
            ShareDestinationNavHost(destination = destination, onClose = close)
        }
    }
}

/** The sheet's own routes (CONTRACT §C.2): one loader per fetched destination kind. */
@Serializable
internal sealed interface ShareRoute {
    @Serializable
    data class UserLoader(val uid: String) : ShareRoute

    @Serializable
    data class PostLoader(val postId: String) : ShareRoute

    @Serializable
    data class NewsLoader(val newsId: String) : ShareRoute
}

/** iOS ShareDestinationView.content's switch, as the NavHost's start route. */
internal fun startRoute(destination: ShareDestination): Any = when (destination) {
    is ShareDestination.Community -> SharedRoute.Community(cid = destination.cid)
    is ShareDestination.User -> ShareRoute.UserLoader(destination.uid)
    is ShareDestination.Post -> ShareRoute.PostLoader(destination.postId)
    is ShareDestination.News -> ShareRoute.NewsLoader(destination.newsId)
}

@Composable
private fun ShareDestinationNavHost(destination: ShareDestination, onClose: () -> Unit) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = startRoute(destination),
        modifier = Modifier.fillMaxSize(),
    ) {
        composable<ShareRoute.UserLoader> { entry ->
            val uid = entry.toRoute<ShareRoute.UserLoader>().uid
            SharedUserLoader(
                uid = uid,
                nav = nav,
                navIcon = nav.navIconFor(entry, onClose),
                onBack = { nav.backOrClose(onClose) },
            )
        }
        composable<ShareRoute.PostLoader> { entry ->
            val postId = entry.toRoute<ShareRoute.PostLoader>().postId
            SharedPostLoader(
                postId = postId,
                nav = nav,
                navIcon = nav.navIconFor(entry, onClose),
                onBack = { nav.backOrClose(onClose) },
            )
        }
        composable<ShareRoute.NewsLoader> { entry ->
            val newsId = entry.toRoute<ShareRoute.NewsLoader>().newsId
            SharedNewsLoader(
                newsId = newsId,
                navIcon = nav.navIconFor(entry, onClose),
                onBack = { nav.backOrClose(onClose) },
            )
        }
        sharedDestinations(nav, onCloseHost = onClose)
    }
}

/** iOS SharedUserLoader: GET /api/users/{uid} → community page or profile detail, in place. */
@Composable
private fun SharedUserLoader(
    uid: String,
    nav: NavHostController,
    navIcon: NavIcon,
    onBack: () -> Unit,
) {
    val model: SharedContentModel<FeedCard> = viewModel(key = "shared-user-$uid") {
        SharedContentModel { DefaultSharedContentApi.user(uid) }
    }
    val state by model.state.collectAsStateWithLifecycle()
    when (val load = state) {
        is SharedLoad.Loaded -> {
            val card = load.value
            if (card.isCommunity) {
                CommunityPageScreen(
                    cid = card.uid,
                    onBack = onBack,
                    onOpenMembers = { nav.openCommunityMembers(it) },
                    navIcon = navIcon,
                )
            } else {
                // Plain context, no caller-owned safety: report/block call the API
                // themselves and a block closes the sheet (onBack at the root).
                ProfileDetailScreen(card = card, onBack = onBack, navIcon = navIcon)
            }
        }
        SharedLoad.Loading -> SharedLoadingContent(navIcon = navIcon, onBack = onBack)
        SharedLoad.Failed -> SharedContentUnavailable(navIcon = navIcon, onBack = onBack)
    }
}

/** iOS SharedPostLoader + SharedPostView. */
@Composable
private fun SharedPostLoader(
    postId: String,
    nav: NavHostController,
    navIcon: NavIcon,
    onBack: () -> Unit,
) {
    val model: SharedPostModel = viewModel(key = "shared-post-$postId") { SharedPostModel(postId) }
    val state by model.state.collectAsStateWithLifecycle()
    val errorMessage by model.errorMessage.collectAsStateWithLifecycle()
    val openUrl = rememberInAppBrowser()
    var showComments by rememberSaveable { mutableStateOf(false) }
    when (val load = state) {
        is SharedLoad.Loaded -> {
            val post: CommunityPostCard = load.value
            SharedPostContent(
                post = post,
                navIcon = navIcon,
                onBack = onBack,
                onVote = if (post.kind == "poll") model::vote else null,
                onRsvp = if (post.kind == "event") model::rsvp else null,
                onOpenLink = post.url?.let { url -> { openUrl(url.toString()) } },
                onOpenComments = { showComments = true },
                onOpenCommunity = { cid -> nav.openCommunity(cid) },
                errorMessage = errorMessage,
                onDismissError = model::dismissError,
            )
            if (showComments) {
                CommentsSheet(post = post, onDismissRequest = { showComments = false })
            }
        }
        SharedLoad.Loading -> SharedLoadingContent(navIcon = navIcon, onBack = onBack)
        SharedLoad.Failed -> SharedContentUnavailable(navIcon = navIcon, onBack = onBack)
    }
}

/** iOS SharedNewsLoader: GET /api/news/{id} → NewsDetailContent; "Read" opens the story + a click event. */
@Composable
private fun SharedNewsLoader(
    newsId: String,
    navIcon: NavIcon,
    onBack: () -> Unit,
) {
    val model: SharedContentModel<NewsCard> = viewModel(key = "shared-news-$newsId") {
        SharedContentModel { DefaultSharedContentApi.news(newsId) }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val openUrl = rememberInAppBrowser()
    when (val load = state) {
        is SharedLoad.Loaded -> {
            val item = load.value
            SharedNewsContent(
                item = item,
                navIcon = navIcon,
                onBack = onBack,
                onReadFullStory = {
                    // iOS: `guard let url = item.url else { return }` — no URL, no click event.
                    item.url?.let { url ->
                        openUrl(url.toString())
                        ContentEvents.click("news/${item.newsId}")
                    }
                },
            )
        }
        SharedLoad.Loading -> SharedLoadingContent(navIcon = navIcon, onBack = onBack)
        SharedLoad.Failed -> SharedContentUnavailable(navIcon = navIcon, onBack = onBack)
    }
}
