package app.drokpo.android.core.model

import android.net.Uri
import kotlinx.serialization.Serializable

// MARK: - Comments

/**
 * One comment (or, when `parentId` is set, reply) on a community post.
 * Author fields are a snapshot taken at creation time, same convention as a
 * post's communityName/communityLogoUrl.
 */
@Serializable
data class CommentCard(
    val commentId: String,
    val authorUid: String? = null,
    val authorKind: String? = null, // "person" | "community"
    val authorName: String? = null,
    val authorPhotoUrl: String? = null,
    val text: String? = null,
    val audioUrl: String? = null,
    val audioDurationSec: Int? = null,
    /**
     * null = a top-level comment; otherwise the top-level comment this
     * thread belongs to (replying to a reply is coerced server-side).
     */
    val parentId: String? = null,
    val replyCount: Int? = null,
    val likeCount: Int? = null,
    val dislikeCount: Int? = null,
    val myVote: String? = null, // "like" | "dislike" | null
    val createdAt: String? = null,
) {
    val id: String get() = commentId
    val isCommunityAuthor: Boolean get() = authorKind == "community"
    val isTopLevel: Boolean get() = parentId == null

    val authorPhoto: Photo?
        get() = authorPhotoUrl?.let { Photo(storagePath = "comment-author-$commentId", url = it) }

    val audioURL: Uri? get() = audioUrl.toUriOrNull()

    val relativeCreated: String?
        get() {
            val createdAt = createdAt ?: return null
            val date = parseIso8601(createdAt) ?: return null
            return abbreviatedRelativeString(date)
        }
}

/** GET /api/posts/{postId}/comments */
@Serializable
data class CommentsResponse(
    val comments: List<CommentCard>? = null,
)

/** GET /api/posts/{postId}/comments/{commentId}/replies */
@Serializable
data class RepliesResponse(
    val replies: List<CommentCard>? = null,
)

/** PUT/DELETE /api/posts/{postId}/comments/{commentId}/vote */
@Serializable
data class CommentVoteResult(
    val likeCount: Int? = null,
    val dislikeCount: Int? = null,
    val myVote: String? = null,
)
