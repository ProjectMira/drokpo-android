package app.drokpo.android.features.likes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.drokpo.android.core.ContentEvents
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.features.shared.comments.CommentsSheet
import app.drokpo.android.features.shared.community.CommunityPostContentView
import app.drokpo.android.features.shared.sharing.ShareButton
import app.drokpo.android.features.shared.sharing.ShareableContent
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.rememberInAppBrowser
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * Read-only detail for a saved community post (`LikesRoute.SavedPost`). The snapshot may
 * outlive the live post (the community can unpublish it), so voting/RSVP aren't offered
 * here — just the content and its link.
 */
@Composable
internal fun LikedPostDetailScreen(
    post: CommunityPostCard,
    onBack: () -> Unit,
    onOpenCommunity: (cid: String) -> Unit,
    modifier: Modifier = Modifier,
    navIcon: NavIcon = NavIcon.Back,
) {
    var showComments by rememberSaveable { mutableStateOf(false) }
    val openUrl = rememberInAppBrowser()
    // Inside the NavHost this is the destination's back stack entry: like the pushes, a tap only
    // acts while the screen is on top, and a fast double tap opens one Custom Tab.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val browserTaps = remember { RepeatTapGuard() }
    val onOpenLink: (() -> Unit)? = remember(post, openUrl, lifecycle, browserTaps) {
        post.url?.let { url ->
            {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    browserTaps.run {
                        openUrl(url.toString())
                        ContentEvents.click("posts/${post.postId}")
                    }
                }
            }
        }
    }
    LikedPostDetailContent(
        post = post,
        onBack = onBack,
        onOpenLink = onOpenLink,
        onOpenComments = { showComments = true },
        onOpenCommunity = onOpenCommunity,
        modifier = modifier,
        navIcon = navIcon,
    )
    if (showComments) {
        CommentsSheet(post, onDismissRequest = { showComments = false })
    }
}

/** Inline title `communityName ?: "Saved post"`, a Share button, and the post content (16dp padding). */
@Composable
internal fun LikedPostDetailContent(
    post: CommunityPostCard,
    onBack: () -> Unit,
    onOpenLink: (() -> Unit)?,
    onOpenComments: () -> Unit,
    onOpenCommunity: (cid: String) -> Unit,
    modifier: Modifier = Modifier,
    navIcon: NavIcon = NavIcon.Back,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = post.communityName ?: "Saved post",
                navIcon = navIcon,
                onNavIcon = onBack,
                actions = { ShareButton(ShareableContent.Post(post)) },
            )
        },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            CommunityPostContentView(
                post = post,
                modifier = Modifier.padding(16.dp),
                onVote = null,
                onRsvp = null,
                onOpenLink = onOpenLink,
                onOpenComments = onOpenComments,
                onOpenCommunity = onOpenCommunity,
            )
        }
    }
}
