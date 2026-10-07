package app.drokpo.android.features.shared.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.features.shared.comments.CommentsSheet
import app.drokpo.android.features.shared.sharing.ShareButton
import app.drokpo.android.features.shared.sharing.ShareableContent
import app.drokpo.android.navigation.openCommunity
import app.drokpo.android.navigation.sharedDestinations
import app.drokpo.android.ui.components.DrokpoSheet
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.LocalInsideSheet
import app.drokpo.android.ui.components.LocalSheetDismiss
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.SecondaryButton
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.serialization.Serializable

/** Start destination of the detail sheet's own NavHost: the post itself. */
@Serializable
internal data object PostDetailRoute

/**
 * Tap-through detail for a community post: full body, poll voting (if a poll), RSVPing (if an
 * event), a link CTA (if it has one), and — in owner mode — a publish/unpublish toggle. Shared by
 * the Discover deck (FeedScreen) and the community's own page (CommunityPageScreen) so both
 * surfaces render a post identically. Port of CommunityPostDetailSheet (CONTRACT §B.9).
 *
 * Self-contained DrokpoSheet with its own NavHost, like iOS's own NavigationStack: start = the
 * post; the header pushes the community's page inside the sheet. The caller owns the vote/RSVP
 * network calls and passes the updated [post] back in. [allowPartialHeight] = iOS detents
 * `[.medium, .large]` (Feed).
 */
@Composable
fun CommunityPostDetailSheet(
    post: CommunityPostCard,
    onVote: ((optionId: String) -> Unit)?,
    onRsvp: ((going: Boolean) -> Unit)?,
    onOpenLink: (() -> Unit)?,
    onDismissRequest: () -> Unit,
    ownerMode: Boolean = false,
    onTogglePublish: (() -> Unit)? = null,
    allowPartialHeight: Boolean = false,
) {
    // The NavHost graph is built once; destinations read the caller's latest values through these.
    val currentPost by rememberUpdatedState(post)
    val currentOnVote by rememberUpdatedState(onVote)
    val currentOnRsvp by rememberUpdatedState(onRsvp)
    val currentOnOpenLink by rememberUpdatedState(onOpenLink)
    val currentOnTogglePublish by rememberUpdatedState(onTogglePublish)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentOwnerMode by rememberUpdatedState(ownerMode)

    DrokpoSheet(onDismissRequest = onDismissRequest, skipPartiallyExpanded = !allowPartialHeight) {
        // Close (X) slides the sheet down like iOS dismiss().
        val sheetDismiss = LocalSheetDismiss.current
        val close: () -> Unit = { sheetDismiss?.invoke() ?: currentOnDismiss() }
        val nav = rememberNavController()
        NavHost(
            navController = nav,
            startDestination = PostDetailRoute,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable<PostDetailRoute> {
                var showComments by rememberSaveable { mutableStateOf(false) }
                CommunityPostDetailContent(
                    post = currentPost,
                    ownerMode = currentOwnerMode,
                    onVote = currentOnVote,
                    onRsvp = currentOnRsvp,
                    onOpenLink = currentOnOpenLink,
                    onTogglePublish = currentOnTogglePublish,
                    onClose = close,
                    onOpenComments = { showComments = true },
                    onOpenCommunity = { cid -> nav.openCommunity(cid) },
                )
                if (showComments) {
                    CommentsSheet(post = currentPost, onDismissRequest = { showComments = false })
                }
            }
            sharedDestinations(nav, onCloseHost = close)
        }
    }
}

/** The sheet's root page: top bar ("Close", title, Share) over the scrolling post. */
@Composable
internal fun CommunityPostDetailContent(
    post: CommunityPostCard,
    ownerMode: Boolean,
    onVote: ((optionId: String) -> Unit)?,
    onRsvp: ((going: Boolean) -> Unit)?,
    onOpenLink: (() -> Unit)?,
    onTogglePublish: (() -> Unit)?,
    onClose: () -> Unit,
    onOpenComments: () -> Unit,
    onOpenCommunity: ((cid: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val insideSheet = LocalInsideSheet.current
    Column(
        modifier
            .fillMaxSize()
            .background(DrokpoTheme.colors.background),
    ) {
        DrokpoTopBar(
            title = post.communityName ?: "Community post",
            navIcon = NavIcon.Close,
            onNavIcon = onClose,
            actions = { ShareButton(ShareableContent.Post(post)) },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .then(if (insideSheet) Modifier else Modifier.navigationBarsPadding())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CommunityPostContentView(
                post = post,
                onVote = onVote,
                onRsvp = onRsvp,
                onOpenLink = onOpenLink,
                onOpenComments = onOpenComments,
                onOpenCommunity = onOpenCommunity,
            )
            if (ownerMode) {
                OwnerControls(post, onTogglePublish)
            }
        }
    }
}

/** Divider, then the unpublished notice and the Republish / Unpublish toggle. */
@Composable
private fun ColumnScope.OwnerControls(post: CommunityPostCard, onTogglePublish: (() -> Unit)?) {
    val colors = DrokpoTheme.colors
    val inactive = post.active == false
    HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (inactive) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = null,
                    tint = colors.orange,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    "Unpublished — only you can see this",
                    style = DrokpoTheme.typography.subheadline,
                    color = colors.orange,
                )
            }
        }
        if (onTogglePublish != null) {
            SecondaryButton(
                text = if (inactive) "Republish" else "Unpublish",
                onClick = onTogglePublish,
                tint = if (inactive) colors.green else colors.orange,
            )
        }
    }
}
