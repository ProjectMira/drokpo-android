package app.drokpo.android.features.shared.comments

import androidx.compose.runtime.Composable
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.features.StubSheet

/** Port of CommentsSheet (+ CommentsModel). Self-contained DrokpoSheet (skipPartiallyExpanded = false,
 *  iOS [.medium, .large]); title "Comments", Close. Builds CommentsModel(post.postId,
 *  postOwnerCid = post.communityId, myUid = AppGraph.session.uid) fresh per presentation.
 *  (CONTRACT §B.10 — stub.) */
@Composable
fun CommentsSheet(post: CommunityPostCard, onDismissRequest: () -> Unit) {
    StubSheet("CommentsSheet(${post.postId})", "Comments", onDismissRequest, skipPartiallyExpanded = false)
}
