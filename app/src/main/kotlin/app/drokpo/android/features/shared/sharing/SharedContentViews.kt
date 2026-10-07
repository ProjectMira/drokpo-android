package app.drokpo.android.features.shared.sharing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.features.shared.community.CommunityPostContentView
import app.drokpo.android.features.shared.news.NewsDetailContent
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.EmptyState
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.LoadingState
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.theme.DrokpoTheme

// Stateless bodies of ShareDestinationSheet's loader routes — renderable from
// fixtures (debug catalog) without network. Every one keeps the sheet's
// inline navigation bar with the leading Close/Back button; iOS sets no title
// until the content has loaded.

/** iOS `ProgressView()` while a shared item is being fetched. */
@Composable
internal fun SharedLoadingContent(navIcon: NavIcon, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { DrokpoTopBar(title = "", navIcon = navIcon, onNavIcon = onBack) },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        LoadingState(Modifier.padding(padding))
    }
}

/**
 * Shown when a shared item can't be fetched — deleted account, unpublished
 * post, or a block relationship (the backend 404s all of these alike).
 */
@Composable
internal fun SharedContentUnavailable(navIcon: NavIcon, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { DrokpoTopBar(title = "", navIcon = navIcon, onNavIcon = onBack) },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        EmptyState(
            icon = Icons.Filled.Link,
            title = "Content unavailable",
            message = "It may have been removed, or isn't available to you.",
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        )
    }
}

/**
 * iOS SharedPostView: a live community post reached through a share link —
 * voting, RSVP, comments, and the link CTA all work, same as the Discover
 * detail sheet. Each callback null ⇒ that affordance is hidden (the caller
 * passes vote only for polls, RSVP only for events, the link only when the
 * post has one).
 */
@Composable
internal fun SharedPostContent(
    post: CommunityPostCard,
    navIcon: NavIcon,
    onBack: () -> Unit,
    onVote: ((optionId: String) -> Unit)?,
    onRsvp: ((going: Boolean) -> Unit)?,
    onOpenLink: (() -> Unit)?,
    onOpenComments: () -> Unit,
    onOpenCommunity: (cid: String) -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = post.communityName ?: "Community post",
                navIcon = navIcon,
                onNavIcon = onBack,
                actions = { ShareButton(ShareableContent.Post(post)) },
            )
        },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        // iOS `ScrollView { CommunityPostContentView(…).padding() }`.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
        ) {
            CommunityPostContentView(
                post = post,
                onVote = onVote,
                onRsvp = onRsvp,
                onOpenLink = onOpenLink,
                onOpenComments = onOpenComments,
                onOpenCommunity = onOpenCommunity,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    ErrorAlert(message = errorMessage, onDismiss = onDismissError)
}

/** iOS SharedNewsLoader's loaded body: NewsDetailContent in a scroll view, titled "News", with a ShareButton. */
@Composable
internal fun SharedNewsContent(
    item: NewsCard,
    navIcon: NavIcon,
    onBack: () -> Unit,
    onReadFullStory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = "News",
                navIcon = navIcon,
                onNavIcon = onBack,
                actions = { ShareButton(ShareableContent.News(item)) },
            )
        },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        // NewsDetailContent leaves scrolling to its caller (CONTRACT §B.9): iOS `ScrollView { NewsDetailContent }`.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding),
        ) {
            NewsDetailContent(item = item, onReadFullStory = onReadFullStory, modifier = Modifier.fillMaxWidth())
        }
    }
}
