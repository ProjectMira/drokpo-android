package app.drokpo.android.features.shared.comments

import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.MediaUploader
import app.drokpo.android.core.Safety
import app.drokpo.android.core.model.CommentCard
import app.drokpo.android.core.model.CommentIn
import app.drokpo.android.core.model.CommentVoteIn
import app.drokpo.android.core.model.CommentVoteResult
import app.drokpo.android.core.model.CommentsResponse
import app.drokpo.android.core.model.RepliesResponse
import java.io.File

/**
 * Every network call [CommentsModel] makes, behind one seam so the model's
 * state machine runs against a fake in JVM tests. [ApiCommentsRepository] is
 * the production implementation — the same paths, methods and bodies as iOS.
 */
internal interface CommentsRepository {
    /** GET /api/posts/{postId}/comments[?before={commentId}] — top-level comments, newest first. */
    suspend fun comments(postId: String, before: String?): List<CommentCard>

    /** GET /api/posts/{postId}/comments/{parentId}/replies — one thread, oldest first. */
    suspend fun replies(postId: String, parentId: String): List<CommentCard>

    /** POST /api/posts/{postId}/comments → the created comment (with a real createdAt). */
    suspend fun create(postId: String, body: CommentIn): CommentCard

    /** commentAudio/{uid}/{uuid}.m4a → storage path (the backend resolves the URL). */
    suspend fun uploadCommentAudio(file: File): String

    /** DELETE /api/posts/{postId}/comments/{commentId} (author or the post's community). */
    suspend fun delete(postId: String, commentId: String)

    /** PUT …/vote {value} for "like"/"dislike", DELETE …/vote to clear it. */
    suspend fun vote(postId: String, commentId: String, value: String?): CommentVoteResult

    /** POST /api/reports. */
    suspend fun report(reportedUid: String, reason: String, note: String)

    /** POST /api/blocks/{uid} + the local BlockStore record. */
    suspend fun block(uid: String, displayName: String?)
}

internal object ApiCommentsRepository : CommentsRepository {
    override suspend fun comments(postId: String, before: String?): List<CommentCard> =
        ApiClient.get<CommentsResponse>(
            "/api/posts/$postId/comments",
            if (before != null) listOf("before" to before) else emptyList(),
        ).comments.orEmpty()

    override suspend fun replies(postId: String, parentId: String): List<CommentCard> =
        ApiClient.get<RepliesResponse>("/api/posts/$postId/comments/$parentId/replies").replies.orEmpty()

    override suspend fun create(postId: String, body: CommentIn): CommentCard =
        ApiClient.post<CommentCard>("/api/posts/$postId/comments", body)

    override suspend fun uploadCommentAudio(file: File): String = MediaUploader.uploadCommentAudio(file)

    override suspend fun delete(postId: String, commentId: String) {
        ApiClient.delete<EmptyResponse>("/api/posts/$postId/comments/$commentId")
    }

    override suspend fun vote(postId: String, commentId: String, value: String?): CommentVoteResult =
        if (value != null) {
            ApiClient.put<CommentVoteResult>("/api/posts/$postId/comments/$commentId/vote", CommentVoteIn(value = value))
        } else {
            ApiClient.delete<CommentVoteResult>("/api/posts/$postId/comments/$commentId/vote")
        }

    override suspend fun report(reportedUid: String, reason: String, note: String) {
        Safety.report(reportedUid = reportedUid, reason = reason, note = note)
    }

    override suspend fun block(uid: String, displayName: String?) {
        Safety.block(uid = uid, displayName = displayName)
    }
}
