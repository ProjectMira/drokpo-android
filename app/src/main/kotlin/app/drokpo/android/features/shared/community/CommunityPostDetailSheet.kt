package app.drokpo.android.features.shared.community

import androidx.compose.runtime.Composable
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.features.StubSheet

/** Port of CommunityPostDetailSheet. Self-contained DrokpoSheet with its own NavHost (start = the post;
 *  header → SharedRoute.Community inside the sheet, like iOS's own NavigationStack); "Close" +
 *  ShareButton(.Post) in its top bar; hosts CommentsSheet. The caller owns vote/RSVP network calls
 *  and passes the updated `post` back in. allowPartialHeight = iOS detents [.medium, .large] (Feed).
 *  (CONTRACT §B.9 — stub.) */
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
    StubSheet(
        name = "CommunityPostDetailSheet(${post.postId})",
        title = post.communityName ?: "Community post",
        onDismissRequest = onDismissRequest,
        skipPartiallyExpanded = !allowPartialHeight,
    )
}
