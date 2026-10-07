package app.drokpo.android.features.shared.comments

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.CommentCard
import app.drokpo.android.core.model.CommentIn
import app.drokpo.android.core.model.CommentVoteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** viewModelScope runs on Dispatchers.Main — point it at the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

internal fun comment(
    id: String,
    author: String? = "u-$id",
    parentId: String? = null,
    replyCount: Int? = 0,
    likeCount: Int? = 0,
    dislikeCount: Int? = 0,
    myVote: String? = null,
    text: String? = "Comment $id",
    name: String? = "Author $id",
) = CommentCard(
    commentId = id,
    authorUid = author,
    authorKind = "person",
    authorName = name,
    text = text,
    parentId = parentId,
    replyCount = replyCount,
    likeCount = likeCount,
    dislikeCount = dislikeCount,
    myVote = myVote,
)

/** Records every call; each endpoint's behaviour is a swappable lambda. */
internal class FakeCommentsRepository : CommentsRepository {
    val calls = mutableListOf<String>()

    var pages: (before: String?) -> List<CommentCard> = { emptyList() }
    var repliesFor: (parentId: String) -> List<CommentCard> = { emptyList() }
    var created: (CommentIn) -> CommentCard = { body -> comment("new", author = "me", parentId = body.parentId, text = body.text) }
    var uploadPath: (File) -> String = { "commentAudio/me/${it.name}" }
    var voteResult: (commentId: String, value: String?) -> CommentVoteResult = { _, value ->
        CommentVoteResult(likeCount = if (value == "like") 1 else 0, dislikeCount = if (value == "dislike") 1 else 0, myVote = value)
    }
    var failure: Exception? = null
    var failOnly: Set<String> = emptySet()

    /** When set, every call suspends until completed — for in-flight assertions. */
    var gate: CompletableDeferred<Unit>? = null

    /** Limits [gate] to these endpoints ("comments", "replies", "create", …); empty = all. */
    var gateOnly: Set<String> = emptySet()

    val createdBodies = mutableListOf<CommentIn>()
    val reports = mutableListOf<Triple<String, String, String>>()
    val blocks = mutableListOf<Pair<String, String?>>()

    private suspend fun maybeFail(name: String) {
        if (gateOnly.isEmpty() || name in gateOnly) gate?.await()
        val error = failure
        if (error != null && (failOnly.isEmpty() || name in failOnly)) throw error
    }

    override suspend fun comments(postId: String, before: String?): List<CommentCard> {
        calls += "comments($postId,before=$before)"
        maybeFail("comments")
        return pages(before)
    }

    override suspend fun replies(postId: String, parentId: String): List<CommentCard> {
        calls += "replies($postId,$parentId)"
        maybeFail("replies")
        return repliesFor(parentId)
    }

    override suspend fun create(postId: String, body: CommentIn): CommentCard {
        calls += "create($postId)"
        maybeFail("create")
        createdBodies += body
        return created(body)
    }

    override suspend fun uploadCommentAudio(file: File): String {
        calls += "upload(${file.name})"
        maybeFail("upload")
        return uploadPath(file)
    }

    override suspend fun delete(postId: String, commentId: String) {
        calls += "delete($postId,$commentId)"
        maybeFail("delete")
    }

    override suspend fun vote(postId: String, commentId: String, value: String?): CommentVoteResult {
        calls += "vote($postId,$commentId,$value)"
        maybeFail("vote")
        return voteResult(commentId, value)
    }

    override suspend fun report(reportedUid: String, reason: String, note: String) {
        calls += "report($reportedUid)"
        maybeFail("report")
        reports += Triple(reportedUid, reason, note)
    }

    override suspend fun block(uid: String, displayName: String?) {
        calls += "block($uid)"
        maybeFail("block")
        blocks += uid to displayName
    }
}

internal val offline = ApiError.Http(status = 500, message = "Server exploded")
