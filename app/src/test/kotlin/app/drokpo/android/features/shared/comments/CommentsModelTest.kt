package app.drokpo.android.features.shared.comments

import app.drokpo.android.core.model.CommentVoteResult
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CommentsModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeCommentsRepository()

    private fun model(myUid: String? = "me", ownerCid: String? = "c-owner") =
        CommentsModel(postId = "p1", postOwnerCid = ownerCid, myUid = myUid, repository = repo)

    // region Loading & pagination

    @Test fun firstLoadRunsOnCreationAndShowsNewestFirstPage() = runTest {
        repo.pages = { before -> if (before == null) listOf(comment("c3"), comment("c2")) else emptyList() }
        val model = model()
        val state = model.state.value
        assertEquals(listOf("comments(p1,before=null)"), repo.calls)
        assertEquals(listOf("c3", "c2"), state.comments.map { it.commentId })
        assertFalse(state.isLoading)
        assertTrue(state.hasMore)
        assertNull(state.errorMessage)
    }

    @Test fun emptyFirstPageMeansNoMorePagesAndEmptyState() = runTest {
        val model = model()
        assertTrue(model.state.value.comments.isEmpty())
        assertFalse(model.state.value.hasMore)
        assertFalse(model.state.value.isLoading)
    }

    @Test fun spinnerOnlyWhileTheListIsEmpty() = runTest {
        repo.pages = { listOf(comment("c1")) }
        val model = model()
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        model.refresh()
        // A refresh over existing comments keeps them on screen (no full-screen spinner).
        assertFalse(model.state.value.isLoading)
        assertTrue(model.state.value.isRefreshing)
        gate.complete(Unit)
        assertFalse(model.state.value.isRefreshing)
    }

    @Test fun loadFailureSurfacesTheErrorAndStopsTheSpinner() = runTest {
        repo.failure = offline
        val model = model()
        assertEquals("Server exploded", model.state.value.errorMessage)
        assertFalse(model.state.value.isLoading)
        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }

    @Test fun pagesWithBeforeTheLastCommentAndStopsOnAnEmptyPage() = runTest {
        repo.pages = { before ->
            when (before) {
                null -> listOf(comment("c4"), comment("c3"))
                "c3" -> listOf(comment("c2"), comment("c1"))
                else -> emptyList()
            }
        }
        val model = model()
        // Not the last row → nothing.
        model.loadMoreIfNeeded(model.state.value.comments.first())
        assertEquals(1, repo.calls.size)

        model.loadMoreIfNeeded(model.state.value.comments.last())
        assertEquals("comments(p1,before=c3)", repo.calls.last())
        assertEquals(listOf("c4", "c3", "c2", "c1"), model.state.value.comments.map { it.commentId })
        assertTrue(model.state.value.hasMore)

        model.loadMoreIfNeeded(model.state.value.comments.last())
        assertEquals("comments(p1,before=c1)", repo.calls.last())
        assertFalse(model.state.value.hasMore)
        assertFalse(model.state.value.isLoadingMore)

        model.loadMoreIfNeeded(model.state.value.comments.last())
        assertEquals("No more requests once a page came back empty", 3, repo.calls.size)
    }

    @Test fun paginationFailureIsSilentAndRetryable() = runTest {
        repo.pages = { listOf(comment("c2")) }
        val model = model()
        repo.failure = offline
        model.loadMoreIfNeeded(model.state.value.comments.last())
        assertNull(model.state.value.errorMessage)
        assertTrue(model.state.value.hasMore)
        assertFalse(model.state.value.isLoadingMore)
    }

    @Test fun onlyOnePageRequestAtATimeAndDuplicatesAreDropped() = runTest {
        repo.pages = { before -> if (before == null) listOf(comment("c3"), comment("c2")) else listOf(comment("c2"), comment("c1")) }
        val model = model()
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val last = model.state.value.comments.last()
        model.loadMoreIfNeeded(last)
        model.loadMoreIfNeeded(last)
        assertTrue(model.state.value.isLoadingMore)
        gate.complete(Unit)
        assertEquals(2, repo.calls.size)
        assertEquals(listOf("c3", "c2", "c1"), model.state.value.comments.map { it.commentId })
    }

    @Test fun aPageThatRacedAReloadIsDiscarded() = runTest {
        repo.pages = { before -> if (before == null) listOf(comment("c2")) else listOf(comment("old")) }
        val model = model()
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        model.loadMoreIfNeeded(model.state.value.comments.last())
        repo.pages = { before -> if (before == null) listOf(comment("fresh"), comment("c2")) else listOf(comment("old")) }
        model.refresh()
        gate.complete(Unit)
        assertEquals(listOf("fresh", "c2"), model.state.value.comments.map { it.commentId })
    }

    // endregion

    // region Replies

    @Test fun repliesLoadOnceWhenAThreadExpands() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 2)) }
        repo.repliesFor = { listOf(comment("r1", parentId = it), comment("r2", parentId = it)) }
        val model = model()
        model.toggleReplies("c1")
        assertEquals("replies(p1,c1)", repo.calls.last())
        assertEquals(listOf("r1", "r2"), model.state.value.repliesByParent["c1"]?.map { it.commentId })
        assertTrue("c1" in model.state.value.expandedParents)

        model.toggleReplies("c1") // collapse (no UI control, but the toggle is symmetric)
        model.toggleReplies("c1") // re-expand: cached, no second request
        assertEquals(1, repo.calls.count { it.startsWith("replies") })
        assertTrue("c1" in model.state.value.expandedParents)
    }

    @Test fun repliesSpinnerShowsWhileLoading() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 1)) }
        val model = model()
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        model.toggleReplies("c1")
        assertTrue("c1" in model.state.value.expandedParents)
        assertNull(model.state.value.repliesByParent["c1"])
        gate.complete(Unit)
        assertEquals(emptyList<String>(), model.state.value.repliesByParent["c1"]?.map { it.commentId })
    }

    @Test fun repliesFailureCollapsesTheThreadSoItCanBeRetried() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 3)) }
        val model = model()
        repo.failure = offline
        model.toggleReplies("c1")
        assertEquals("Server exploded", model.state.value.errorMessage)
        assertFalse("c1" in model.state.value.expandedParents)
        assertNull(model.state.value.repliesByParent["c1"])
    }

    // endregion

    // region Submit

    @Test fun textCommentIsTrimmedPostedAndInsertedAtTheTop() = runTest {
        repo.pages = { listOf(comment("c1")) }
        repo.created = { body -> comment("c-new", author = "me", text = body.text) }
        val model = model()
        var cleared = false
        model.submit(CommentDraft.Text("  Hello there \n")) { cleared = true }
        assertEquals("Hello there", repo.createdBodies.single().text)
        assertNull(repo.createdBodies.single().parentId)
        assertNull(repo.createdBodies.single().audioStoragePath)
        assertEquals(listOf("c-new", "c1"), model.state.value.comments.map { it.commentId })
        assertTrue(cleared)
        assertFalse(model.state.value.isSending)
    }

    @Test fun blankTextIsNeverSent() = runTest {
        val model = model()
        assertFalse(model.submitNow(CommentDraft.Text("   "), parentId = null))
        assertTrue(repo.createdBodies.isEmpty())
    }

    @Test fun voiceCommentUploadsThenPostsThePathAndDuration() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 0)) }
        val model = model()
        model.setReplyTarget(model.state.value.comments.single())
        model.submit(CommentDraft.Audio(File("clip.m4a"), seconds = 12)) {}
        assertEquals(listOf("upload(clip.m4a)", "create(p1)"), repo.calls.drop(1))
        val body = repo.createdBodies.single()
        assertEquals("commentAudio/me/clip.m4a", body.audioStoragePath)
        assertEquals(12, body.audioDurationSec)
        assertEquals("c1", body.parentId)
        assertNull(body.text)
    }

    @Test fun replyIsAppendedToItsThreadAndBumpsTheCount() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 1)) }
        repo.repliesFor = { listOf(comment("r1", parentId = "c1")) }
        repo.created = { body -> comment("r2", author = "me", parentId = body.parentId) }
        val model = model()
        model.toggleReplies("c1")
        model.setReplyTarget(model.state.value.comments.single())
        var cleared = false
        model.submit(CommentDraft.Text("Me too")) { cleared = true }

        val state = model.state.value
        assertEquals(listOf("r1", "r2"), state.repliesByParent["c1"]?.map { it.commentId })
        assertEquals(2, state.comments.single().replyCount)
        assertTrue("c1" in state.expandedParents)
        assertNull("Reply target clears on success", state.replyTarget)
        assertTrue(cleared)
        assertEquals("No top-level insert for a reply", 1, state.comments.size)
    }

    @Test fun replyToAnUnloadedThreadFetchesTheExistingReplies() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 2)) }
        repo.created = { body -> comment("r3", author = "me", parentId = body.parentId) }
        // The server already has the new reply when the thread is fetched.
        repo.repliesFor = { listOf(comment("r1", parentId = "c1"), comment("r2", parentId = "c1"), comment("r3", parentId = "c1")) }
        val model = model()
        model.setReplyTarget(model.state.value.comments.single())
        model.submit(CommentDraft.Text("Hi")) {}
        val state = model.state.value
        assertEquals(listOf("r1", "r2", "r3"), state.repliesByParent["c1"]?.map { it.commentId })
        assertEquals(3, state.comments.single().replyCount)
        assertTrue("c1" in state.expandedParents)
    }

    @Test fun theThreadBackfillNeverHoldsTheSendSpinner() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 2)) }
        repo.created = { body -> comment("r3", author = "me", parentId = body.parentId) }
        repo.repliesFor = { listOf(comment("r1", parentId = "c1"), comment("r2", parentId = "c1"), comment("r3", parentId = "c1")) }
        val model = model()
        model.setReplyTarget(model.state.value.comments.single())
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        repo.gateOnly = setOf("replies")
        var cleared = false
        model.submit(CommentDraft.Text("Hi")) { cleared = true }

        // GET …/replies is still pending, but the send is done.
        assertEquals("replies(p1,c1)", repo.calls.last())
        model.state.value.let { state ->
            assertFalse(state.isSending)
            assertTrue(cleared)
            assertNull(state.replyTarget)
            assertEquals("The new reply shows right away", listOf("r3"), state.repliesByParent["c1"]?.map { it.commentId })
            assertTrue("c1" in state.expandedParents)
        }

        gate.complete(Unit)
        assertEquals(listOf("r1", "r2", "r3"), model.state.value.repliesByParent["c1"]?.map { it.commentId })
    }

    @Test fun replyToAThreadWithNoRepliesNeedsNoFetch() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 0)) }
        repo.created = { body -> comment("r1", author = "me", parentId = body.parentId) }
        val model = model()
        model.setReplyTarget(model.state.value.comments.single())
        model.submit(CommentDraft.Text("First!")) {}
        assertEquals(0, repo.calls.count { it.startsWith("replies") })
        assertEquals(listOf("r1"), model.state.value.repliesByParent["c1"]?.map { it.commentId })
        assertEquals(1, model.state.value.comments.single().replyCount)
    }

    @Test fun theListScrollsToTopForANewCommentAndARefreshButNotForAReply() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 0)) }
        repo.created = { body -> comment(if (body.parentId == null) "c-new" else "r-new", author = "me", parentId = body.parentId) }
        val model = model()
        assertEquals("Not on the first load", 0, model.state.value.scrollToTopToken)

        model.submit(CommentDraft.Text("Top level")) {}
        assertEquals(1, model.state.value.scrollToTopToken)

        model.setReplyTarget(model.state.value.comments.first { it.commentId == "c1" })
        model.submit(CommentDraft.Text("A reply")) {}
        assertEquals("A reply goes into its thread; the list stays put", 1, model.state.value.scrollToTopToken)

        repo.pages = { listOf(comment("c-newer"), comment("c-new"), comment("c1")) }
        model.refresh()
        assertEquals(2, model.state.value.scrollToTopToken)

        repo.failure = offline
        model.refresh()
        assertEquals("A failed refresh leaves the list alone", 2, model.state.value.scrollToTopToken)

        repo.failure = null
        model.blockAuthor(comment("c-newer", author = "u-bad"))
        assertEquals("The reload after a block isn't a refresh", 2, model.state.value.scrollToTopToken)
    }

    @Test fun failedSubmitKeepsTheDraftAndTheReplyTarget() = runTest {
        repo.pages = { listOf(comment("c1")) }
        val model = model()
        model.setReplyTarget(model.state.value.comments.single())
        repo.failure = offline
        var cleared = false
        model.submit(CommentDraft.Text("Hello")) { cleared = true }
        assertFalse(cleared)
        assertEquals("c1", model.state.value.replyTarget?.commentId)
        assertEquals("Server exploded", model.state.value.errorMessage)
        assertFalse(model.state.value.isSending)
        assertEquals(1, model.state.value.comments.size)
    }

    @Test fun failedUploadNeverPostsTheComment() = runTest {
        val model = model()
        repo.failure = offline
        repo.failOnly = setOf("upload")
        assertFalse(model.submitNow(CommentDraft.Audio(File("clip.m4a"), 3), parentId = null))
        assertTrue(repo.createdBodies.isEmpty())
    }

    @Test fun aSecondSendWhileSendingIsIgnored() = runTest {
        val model = model()
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        model.submit(CommentDraft.Text("one")) {}
        assertTrue(model.state.value.isSending)
        model.submit(CommentDraft.Text("two")) {}
        gate.complete(Unit)
        assertEquals(listOf("one"), repo.createdBodies.map { it.text })
    }

    // endregion

    // region Delete

    @Test fun deletingAReplyDecrementsItsParent() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 2)) }
        repo.repliesFor = { listOf(comment("r1", parentId = "c1"), comment("r2", parentId = "c1")) }
        val model = model()
        model.toggleReplies("c1")
        model.delete(model.state.value.repliesByParent.getValue("c1").first())
        assertEquals("delete(p1,r1)", repo.calls.last())
        assertEquals(listOf("r2"), model.state.value.repliesByParent["c1"]?.map { it.commentId })
        assertEquals(1, model.state.value.comments.single().replyCount)
    }

    @Test fun deletingATopLevelCommentDropsItsThread() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 1), comment("c2")) }
        repo.repliesFor = { listOf(comment("r1", parentId = "c1")) }
        val model = model()
        model.toggleReplies("c1")
        model.setReplyTarget(model.state.value.comments.first())
        model.delete(model.state.value.comments.first())
        val state = model.state.value
        assertEquals(listOf("c2"), state.comments.map { it.commentId })
        assertNull(state.repliesByParent["c1"])
        assertFalse("c1" in state.expandedParents)
        assertNull("Can't keep replying to a deleted comment", state.replyTarget)
    }

    @Test fun failedDeleteKeepsTheCommentAndShowsTheError() = runTest {
        repo.pages = { listOf(comment("c1")) }
        val model = model()
        repo.failure = offline
        model.delete(model.state.value.comments.single())
        assertEquals(1, model.state.value.comments.size)
        assertEquals("Server exploded", model.state.value.errorMessage)
    }

    @Test fun replyCountNeverGoesNegative() {
        val state = CommentsUiState(comments = listOf(comment("c1", replyCount = 0)))
        assertEquals(0, state.bumpReplyCount("c1", by = -1).comments.single().replyCount)
        assertEquals(1, CommentsUiState(comments = listOf(comment("c1", replyCount = null))).bumpReplyCount("c1", 1).comments.single().replyCount)
    }

    // endregion

    // region Votes

    @Test fun voteIsOptimisticThenReconciledWithTheServer() = runTest {
        repo.pages = { listOf(comment("c1", likeCount = 4, dislikeCount = 1)) }
        repo.voteResult = { _, _ -> CommentVoteResult(likeCount = 7, dislikeCount = 1, myVote = "like") }
        val model = model()
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        model.vote(model.state.value.comments.single(), "like")
        model.state.value.comments.single().let {
            assertEquals("like", it.myVote)
            assertEquals(5, it.likeCount)
        }
        gate.complete(Unit)
        model.state.value.comments.single().let {
            assertEquals("Server counts win", 7, it.likeCount)
            assertEquals("like", it.myVote)
        }
        assertEquals("vote(p1,c1,like)", repo.calls.last())
    }

    @Test fun failedVoteRollsBack() = runTest {
        repo.pages = { listOf(comment("c1", likeCount = 2, dislikeCount = 0, myVote = "like")) }
        val model = model()
        repo.failure = offline
        model.vote(model.state.value.comments.single(), "dislike")
        model.state.value.comments.single().let {
            assertEquals("like", it.myVote)
            assertEquals(2, it.likeCount)
            assertEquals(0, it.dislikeCount)
        }
        assertEquals("Server exploded", model.state.value.errorMessage)
    }

    @Test fun clearingAVoteSendsNullAndTapsWhileInFlightAreIgnored() = runTest {
        repo.pages = { listOf(comment("c1", likeCount = 1, myVote = "like")) }
        val model = model()
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val current = model.state.value.comments.single()
        model.vote(current, nextVote(current.myVote, VOTE_LIKE))
        model.vote(model.state.value.comments.single(), "dislike")
        gate.complete(Unit)
        assertEquals(listOf("vote(p1,c1,null)"), repo.calls.filter { it.startsWith("vote") })
        assertNull(model.state.value.comments.single().myVote)
    }

    @Test fun votesOnRepliesUpdateTheThread() = runTest {
        repo.pages = { listOf(comment("c1", replyCount = 1)) }
        repo.repliesFor = { listOf(comment("r1", parentId = "c1", likeCount = 0)) }
        val model = model()
        model.toggleReplies("c1")
        model.vote(model.state.value.repliesByParent.getValue("c1").single(), "like")
        assertEquals("like", model.state.value.repliesByParent.getValue("c1").single().myVote)
        assertEquals(1, model.state.value.repliesByParent.getValue("c1").single().likeCount)
    }

    @Test fun optimisticVoteArithmetic() {
        val neutral = comment("c", likeCount = 3, dislikeCount = 2)
        assertEquals(4 to 2, neutral.withVote("like").let { it.likeCount to it.dislikeCount })
        assertEquals(3 to 3, neutral.withVote("dislike").let { it.likeCount to it.dislikeCount })
        val liked = comment("c", likeCount = 3, dislikeCount = 2, myVote = "like")
        assertEquals(2 to 3, liked.withVote("dislike").let { it.likeCount to it.dislikeCount })
        assertEquals(2 to 2, liked.withVote(null).let { it.likeCount to it.dislikeCount })
        assertEquals(0, comment("c", likeCount = 0, myVote = "like").withVote(null).likeCount)
        assertEquals(1, comment("c", likeCount = null, dislikeCount = null).withVote("like").likeCount)
    }

    @Test fun tappingTheActiveVoteClearsIt() {
        assertNull(nextVote("like", VOTE_LIKE))
        assertEquals("dislike", nextVote("like", VOTE_DISLIKE))
        assertEquals("like", nextVote(null, VOTE_LIKE))
        assertNull(nextVote("dislike", VOTE_DISLIKE))
    }

    // endregion

    // region Safety

    @Test fun reportCarriesTheCommentAndPostInTheNote() = runTest {
        val model = model()
        model.reportAuthor(comment("c9", author = "u-bad"), "Spam")
        assertEquals(Triple("u-bad", "Spam", "Comment c9 on post p1"), repo.reports.single())
    }

    @Test fun reportFailureShowsTheError() = runTest {
        val model = model()
        repo.failure = offline
        model.reportAuthor(comment("c9", author = "u-bad"), "Spam")
        assertEquals("Server exploded", model.state.value.errorMessage)
    }

    @Test fun blockRecordsThenReloadsAndDropsTheirReplies() = runTest {
        repo.pages = { listOf(comment("c1", author = "u-good", replyCount = 2), comment("c2", author = "u-bad")) }
        repo.repliesFor = { listOf(comment("r1", author = "u-bad", parentId = "c1"), comment("r2", author = "u-good", parentId = "c1")) }
        val model = model()
        model.toggleReplies("c1")
        model.setReplyTarget(model.state.value.comments.first())
        // The backend drops blocked authors' comments from the next page.
        repo.pages = { listOf(comment("c1", author = "u-good", replyCount = 2)) }
        model.blockAuthor(comment("c2", author = "u-bad", name = "Bad Actor"))

        assertEquals("u-bad" to "Bad Actor", repo.blocks.single())
        assertEquals(listOf("c1"), model.state.value.comments.map { it.commentId })
        assertEquals(listOf("r2"), model.state.value.repliesByParent["c1"]?.map { it.commentId })
        assertEquals("comments(p1,before=null)", repo.calls.last())
        assertEquals("Replying to someone else stays", "c1", model.state.value.replyTarget?.commentId)
    }

    @Test fun safetyActionsNeedAnAuthor() = runTest {
        val model = model()
        val tombstone = comment("t1", author = null, name = "Deleted account")
        model.reportAuthor(tombstone, "Spam")
        model.blockAuthor(tombstone)
        assertTrue(repo.reports.isEmpty())
        assertTrue(repo.blocks.isEmpty())
    }

    // endregion

    // region Permissions

    @Test fun deleteIsForTheAuthorAndThePostsCommunity() {
        val member = CommentsUiState(myUid = "me", postOwnerCid = "c-owner")
        assertTrue(member.canDelete(comment("a", author = "me")))
        assertFalse(member.canDelete(comment("b", author = "someone")))
        val owner = CommentsUiState(myUid = "c-owner", postOwnerCid = "c-owner")
        assertTrue(owner.canDelete(comment("b", author = "someone")))
        val signedOut = CommentsUiState(myUid = null, postOwnerCid = null)
        assertFalse("No uid never matches a null owner", signedOut.canDelete(comment("x", author = null)))
    }

    @Test fun reportIsNeverOfferedOnYourOwnCommentOrATombstone() {
        val state = CommentsUiState(myUid = "me", postOwnerCid = "c-owner")
        assertFalse(state.canReport(comment("a", author = "me")))
        assertTrue(state.canReport(comment("b", author = "someone")))
        assertFalse(state.canReport(comment("t", author = null)))
        assertTrue(state.isMine(comment("a", author = "me")))
        assertFalse(CommentsUiState(myUid = null).isMine(comment("x", author = null)))
    }

    // endregion

    // region Dismissal

    @Test fun writesOutliveTheSheetButItsReloadsDoNot() = runTest {
        repo.pages = { listOf(comment("c1", author = "u-bad")) }
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer { CommentsModel("p1", postOwnerCid = "c-owner", myUid = "me", repository = repo, writeScope = backgroundScope) }
        }
        val model = ViewModelProvider.create(store, factory)[CommentsModel::class]
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        var cleared = false
        model.submit(CommentDraft.Text("bye")) { cleared = true }
        model.blockAuthor(model.state.value.comments.single())
        store.clear() // the sheet is dismissed right after the taps
        gate.complete(Unit)
        runCurrent() // backgroundScope work only runs on explicit scheduler steps
        assertEquals("The comment is still posted", listOf("bye"), repo.createdBodies.map { it.text })
        assertTrue(cleared)
        assertEquals("…and the block still lands", listOf("u-bad"), repo.blocks.map { it.first })
        assertEquals("No reload for a closed sheet", 1, repo.calls.count { it.startsWith("comments") })
    }

    // endregion

    @Test fun labels() {
        assertEquals("View 1 reply", viewRepliesLabel(1))
        assertEquals("View 2 replies", viewRepliesLabel(2))
        assertEquals("View 12 replies", viewRepliesLabel(12))
        assertEquals("0s / 60s", recordingLabel(0))
        assertEquals("42s / 60s", recordingLabel(42))
    }

    @Test fun composerDraftRules() {
        assertFalse(composerCanSend("   \n ", recorded = null))
        assertTrue(composerCanSend(" hi ", recorded = null))
        val clip = app.drokpo.android.features.shared.audio.RecordedClip(File("a.m4a"), 4)
        assertTrue(composerCanSend("", recorded = clip))
        assertEquals(CommentDraft.Text("hi"), composerDraft("  hi \n", recorded = null))
        assertEquals("A recorded clip wins over typed text", CommentDraft.Audio(clip.file, 4), composerDraft("typed", recorded = clip))
    }
}
