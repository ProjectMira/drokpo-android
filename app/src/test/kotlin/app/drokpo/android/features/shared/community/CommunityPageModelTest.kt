package app.drokpo.android.features.shared.community

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.Poll
import app.drokpo.android.core.model.PollOption
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.VoteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityPageModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val cid = "c-tat"
    private val preview = CommunityProfile(uid = cid, name = "TAT (preview)", memberCount = 9, joined = false)
    private val detail = CommunityProfile(uid = cid, name = "TAT", memberCount = 10, joined = false, verification = "verified")

    private fun post(id: String, kind: String = "announcement", active: Boolean? = true) =
        CommunityPostCard(postId = id, communityId = cid, kind = kind, title = "Post $id", active = active)

    private fun page(prefix: String, count: Int) = (1..count).map { post("$prefix$it") }

    private class FakeRepository : CommunityPageRepository {
        val calls = mutableListOf<String>()
        var community: suspend () -> CommunityProfile = { error("community() not stubbed") }
        var posts: suspend (before: String?) -> List<CommunityPostCard> = { emptyList() }
        var joinError: Exception? = null
        var voteResult: suspend () -> VoteResult = { VoteResult() }
        var rsvpResult: suspend (Boolean) -> RsvpResult = { RsvpResult() }
        var patchError: Exception? = null
        var reportError: Exception? = null
        var blockError: Exception? = null

        override suspend fun community(cid: String): CommunityProfile {
            calls += "community($cid)"
            return community()
        }

        override suspend fun posts(cid: String, before: String?): List<CommunityPostCard> {
            calls += "posts($cid, before=$before)"
            return posts(before)
        }

        override suspend fun join(cid: String) {
            calls += "join($cid)"
            joinError?.let { throw it }
        }

        override suspend fun leave(cid: String) {
            calls += "leave($cid)"
            joinError?.let { throw it }
        }

        override suspend fun vote(postId: String, optionId: String): VoteResult {
            calls += "vote($postId, $optionId)"
            return voteResult()
        }

        override suspend fun rsvp(postId: String, going: Boolean): RsvpResult {
            calls += "rsvp($postId, $going)"
            return rsvpResult(going)
        }

        override suspend fun setPostActive(postId: String, active: Boolean) {
            calls += "patch($postId, active=$active)"
            patchError?.let { throw it }
        }

        override suspend fun report(cid: String, reason: String) {
            calls += "report($cid, $reason)"
            reportError?.let { throw it }
        }

        override suspend fun block(cid: String, displayName: String?) {
            calls += "block($cid, $displayName)"
            blockError?.let { throw it }
        }
    }

    private fun TestScope.visitor(repo: FakeRepository, preview: CommunityProfile? = this@CommunityPageModelTest.preview): CommunityPageModel {
        val model = CommunityPageModel(cid, ownerMode = false, preview = preview, repository = repo)
        advanceUntilIdle()
        return model
    }

    // region Loading

    @Test
    fun visitorShowsPreviewThenFetchedHeaderAndPosts() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { page("p", 3) }
        }
        val model = CommunityPageModel(cid, ownerMode = false, preview = preview, repository = repo)
        // Before the fetch: the preview header, loading flags up.
        assertEquals(preview, model.state.value.visitorCommunity)
        assertTrue(model.state.value.isLoadingPosts)

        advanceUntilIdle()
        val s = model.state.value
        assertEquals(detail, s.visitorCommunity)
        assertFalse(s.isLoadingHeader)
        assertFalse(s.isLoadingPosts)
        assertEquals(listOf("p1", "p2", "p3"), s.posts.map { it.postId })
        assertTrue(s.hasMorePosts)
        assertEquals(listOf("community($cid)", "posts($cid, before=null)"), repo.calls)
    }

    @Test
    fun ownerModeNeverFetchesTheHeader() = runTest(dispatcher) {
        val repo = FakeRepository().apply { posts = { page("p", 2) } }
        val model = CommunityPageModel(cid, ownerMode = true, preview = null, repository = repo)
        advanceUntilIdle()
        // GET /communities/{cid} 404s for an unverified community — owner mode must not call it.
        assertEquals(listOf("posts($cid, before=null)"), repo.calls)
        assertFalse(model.state.value.isLoadingHeader)
        assertEquals(2, model.state.value.posts.size)
    }

    @Test
    fun emptyFirstPageStopsPagination() = runTest(dispatcher) {
        val repo = FakeRepository().apply { community = { detail } }
        val model = visitor(repo)
        assertTrue(model.state.value.posts.isEmpty())
        assertFalse(model.state.value.hasMorePosts)
        assertFalse(model.state.value.isLoadingPosts)
    }

    @Test
    fun headerFailureShowsErrorAndKeepsHeaderSpinnerLikeIos() = runTest(dispatcher) {
        val repo = FakeRepository().apply { community = { throw ApiError.Http(404, "Community not found") } }
        val model = visitor(repo, preview = null)
        val s = model.state.value
        assertEquals("Community not found", s.errorMessage)
        assertTrue(s.isLoadingHeader)
        assertFalse(s.isLoadingPosts)
        // Posts aren't requested once the header failed (same try block as iOS).
        assertEquals(listOf("community($cid)"), repo.calls)

        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun newestLoadWinsWhenARefreshOverlapsTheFirstLoad() = runTest(dispatcher) {
        val firstPosts = CompletableDeferred<List<CommunityPostCard>>()
        var postCalls = 0
        val repo = FakeRepository().apply {
            community = { detail }
            posts = {
                postCalls += 1
                if (postCalls == 1) firstPosts.await() else page("new", 2)
            }
        }
        val model = CommunityPageModel(cid, ownerMode = false, preview = preview, repository = repo)
        advanceUntilIdle() // first load parked on its posts request
        model.refresh()
        assertTrue(model.state.value.isRefreshing)
        advanceUntilIdle()
        firstPosts.complete(page("stale", 5))
        advanceUntilIdle()

        val s = model.state.value
        assertEquals(listOf("new1", "new2"), s.posts.map { it.postId })
        assertFalse(s.isRefreshing)
        assertFalse(s.isLoadingPosts)
    }

    // endregion

    // region Pagination

    @Test
    fun lastTileAppearingLoadsTheNextPage() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { before ->
                when (before) {
                    null -> page("a", 3)
                    "a3" -> page("b", 2)
                    else -> emptyList()
                }
            }
        }
        val model = visitor(repo)

        // Not the last tile → nothing.
        model.loadMoreIfNeeded(model.state.value.posts[1])
        advanceUntilIdle()
        assertEquals(3, model.state.value.posts.size)

        model.loadMoreIfNeeded(model.state.value.posts.last())
        assertTrue(model.state.value.isLoadingMore)
        // A second appearance while loading doesn't start a duplicate page.
        model.loadMoreIfNeeded(model.state.value.posts.last())
        advanceUntilIdle()
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), model.state.value.posts.map { it.postId })
        assertFalse(model.state.value.isLoadingMore)
        assertEquals(1, repo.calls.count { it == "posts($cid, before=a3)" })

        // b2's page is empty → stop.
        model.loadMoreIfNeeded(model.state.value.posts.last())
        advanceUntilIdle()
        assertFalse(model.state.value.hasMorePosts)
        model.loadMoreIfNeeded(model.state.value.posts.last())
        advanceUntilIdle()
        assertEquals(1, repo.calls.count { it == "posts($cid, before=b2)" })
    }

    @Test
    fun paginationFailureIsSilent() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { before -> if (before == null) page("a", 2) else throw ApiError.Http(500, "boom") }
        }
        val model = visitor(repo)
        model.loadMoreIfNeeded(model.state.value.posts.last())
        advanceUntilIdle()
        val s = model.state.value
        assertNull(s.errorMessage)
        assertFalse(s.isLoadingMore)
        assertTrue(s.hasMorePosts) // the next appearance retries
        assertEquals(2, s.posts.size)
    }

    @Test
    fun serverResendingPageOneForADeletedCursorAppendsNoRepeatsAndStopsPaging() = runTest(dispatcher) {
        // list_posts ignores `before` when that post no longer exists and returns page 1 again.
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { page("a", 3) }
        }
        val model = visitor(repo)
        model.loadMoreIfNeeded(model.state.value.posts.last())
        advanceUntilIdle()

        val s = model.state.value
        // A repeated LazyVerticalGrid key would crash — the list must stay unique.
        assertEquals(listOf("a1", "a2", "a3"), s.posts.map { it.postId })
        assertFalse(s.hasMorePosts)
        assertFalse(s.isLoadingMore)
        // No loop: the next appearance of the last tile doesn't refetch page 1.
        model.loadMoreIfNeeded(model.state.value.posts.last())
        advanceUntilIdle()
        assertEquals(1, repo.calls.count { it == "posts($cid, before=a3)" })
    }

    @Test
    fun pageWithSomeRepeatsKeepsOnlyTheNewPostsAndKeepsPaging() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { before ->
                when (before) {
                    // A duplicate inside the first page is dropped too.
                    null -> page("a", 3) + post("a2")
                    "a3" -> listOf(post("a3"), post("b1"), post("b1"), post("b2"))
                    else -> emptyList()
                }
            }
        }
        val model = visitor(repo)
        assertEquals(listOf("a1", "a2", "a3"), model.state.value.posts.map { it.postId })

        model.loadMoreIfNeeded(model.state.value.posts.last())
        advanceUntilIdle()
        val s = model.state.value
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), s.posts.map { it.postId })
        assertTrue(s.hasMorePosts)
    }

    @Test
    fun pageStartedBeforeARefreshIsDroppedOnceTheRefreshReplacesTheList() = runTest(dispatcher) {
        val parkedPage = CompletableDeferred<List<CommunityPostCard>>()
        var firstPageCalls = 0
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { before ->
                when (before) {
                    null -> {
                        firstPageCalls += 1
                        if (firstPageCalls == 1) page("a", 3) else page("new", 2)
                    }
                    "a3" -> parkedPage.await()
                    else -> emptyList()
                }
            }
        }
        val model = visitor(repo)
        model.loadMoreIfNeeded(model.state.value.posts.last()) // parks on before=a3
        advanceUntilIdle()
        assertTrue(model.state.value.isLoadingMore)

        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf("new1", "new2"), model.state.value.posts.map { it.postId })

        parkedPage.complete(page("b", 2))
        advanceUntilIdle()
        val s = model.state.value
        assertEquals(listOf("new1", "new2"), s.posts.map { it.postId })
        assertFalse(s.isLoadingMore)
        assertTrue(s.hasMorePosts)
    }

    @Test
    fun pageWithAStaleCursorIsDroppedWhenAReloadThatStartedFirstLandsFirst() = runTest(dispatcher) {
        // The reload bumps the load generation *before* loadMore starts, so both share a token —
        // only the last-post check can tell that the page continues a list that's been replaced.
        val reloadPage = CompletableDeferred<List<CommunityPostCard>>()
        val staleCursorPage = CompletableDeferred<List<CommunityPostCard>>()
        var firstPageCalls = 0
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { before ->
                when (before) {
                    null -> {
                        firstPageCalls += 1
                        if (firstPageCalls == 1) page("a", 3) else reloadPage.await()
                    }
                    "a3" -> staleCursorPage.await()
                    else -> emptyList()
                }
            }
        }
        val model = visitor(repo)

        model.refresh() // parks on its first page
        advanceUntilIdle()
        model.loadMoreIfNeeded(model.state.value.posts.last()) // cursor a3, from the old list
        advanceUntilIdle()
        assertEquals(1, repo.calls.count { it == "posts($cid, before=a3)" })

        reloadPage.complete(listOf(post("n1"), post("a1"), post("a2")))
        advanceUntilIdle()
        assertEquals(listOf("n1", "a1", "a2"), model.state.value.posts.map { it.postId })

        // a3 isn't the last post any more: appending would leave a gap (a3 missing) — drop it.
        staleCursorPage.complete(page("b", 2))
        advanceUntilIdle()
        val s = model.state.value
        assertEquals(listOf("n1", "a1", "a2"), s.posts.map { it.postId })
        assertFalse(s.isLoadingMore)
        assertTrue(s.hasMorePosts)
        assertFalse(s.isRefreshing)

        // The next appearance of the (new) last tile pages from the new list.
        model.loadMoreIfNeeded(model.state.value.posts.last())
        advanceUntilIdle()
        assertEquals(1, repo.calls.count { it == "posts($cid, before=a2)" })
    }

    @Test
    fun pageIsKeptWhenTheReloadedListStillEndsAtItsCursor() = runTest(dispatcher) {
        val reloadPage = CompletableDeferred<List<CommunityPostCard>>()
        val nextPage = CompletableDeferred<List<CommunityPostCard>>()
        var firstPageCalls = 0
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { before ->
                when (before) {
                    null -> {
                        firstPageCalls += 1
                        if (firstPageCalls == 1) page("a", 3) else reloadPage.await()
                    }
                    "a3" -> nextPage.await()
                    else -> emptyList()
                }
            }
        }
        val model = visitor(repo)
        model.refresh()
        advanceUntilIdle()
        model.loadMoreIfNeeded(model.state.value.posts.last())
        advanceUntilIdle()

        reloadPage.complete(page("a", 3)) // nothing changed on the server
        advanceUntilIdle()
        nextPage.complete(page("b", 2))
        advanceUntilIdle()
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), model.state.value.posts.map { it.postId })
    }

    // endregion

    // region Composer reload

    @Test
    fun reloadAndWaitReturnsOnlyOnceTheNewPostsAreInState() = runTest(dispatcher) {
        val reloadPage = CompletableDeferred<List<CommunityPostCard>>()
        var firstPageCalls = 0
        val repo = FakeRepository().apply {
            community = { detail }
            posts = {
                firstPageCalls += 1
                if (firstPageCalls == 1) page("a", 2) else reloadPage.await()
            }
        }
        val model = visitor(repo)

        var stateAtReturn: CommunityPageUiState? = null
        val job = launch {
            model.reloadAndWait()
            stateAtReturn = model.state.value
        }
        advanceUntilIdle()
        // Still suspended while the reload's posts are in flight.
        assertFalse(job.isCompleted)
        assertNull(stateAtReturn)
        assertTrue(model.state.value.isLoadingPosts)

        reloadPage.complete(listOf(post("new")) + page("a", 2))
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        val s = stateAtReturn!!
        assertEquals(listOf("new", "a1", "a2"), s.posts.map { it.postId })
        assertFalse(s.isLoadingPosts)
        assertEquals(2, repo.calls.count { it == "community($cid)" })
    }

    // endregion

    // region Join

    @Test
    fun joinAndLeaveAdjustMembershipAfterTheServerAnswers() = runTest(dispatcher) {
        val repo = FakeRepository().apply { community = { detail } }
        val model = visitor(repo)

        model.toggleJoin()
        assertTrue(model.state.value.isJoining)
        // Not optimistic (iOS waits for the server).
        assertEquals(false, model.state.value.visitorCommunity?.joined)
        advanceUntilIdle()
        assertEquals(true, model.state.value.visitorCommunity?.joined)
        assertEquals(11, model.state.value.visitorCommunity?.memberCount)
        assertFalse(model.state.value.isJoining)

        model.toggleJoin()
        advanceUntilIdle()
        assertEquals(false, model.state.value.visitorCommunity?.joined)
        assertEquals(10, model.state.value.visitorCommunity?.memberCount)
        assertEquals(listOf("join($cid)", "leave($cid)"), repo.calls.filter { it.startsWith("join") || it.startsWith("leave") })
    }

    @Test
    fun leaveNeverDropsMemberCountBelowZero() = runTest(dispatcher) {
        val repo = FakeRepository().apply { community = { detail.copy(joined = true, memberCount = 0) } }
        val model = visitor(repo)
        model.toggleJoin()
        advanceUntilIdle()
        assertEquals(0, model.state.value.visitorCommunity?.memberCount)
        assertEquals(false, model.state.value.visitorCommunity?.joined)
    }

    @Test
    fun joinFailureKeepsStateAndShowsError() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            community = { detail }
            joinError = ApiError.Http(403, "Only person accounts can join communities")
        }
        val model = visitor(repo)
        model.toggleJoin()
        advanceUntilIdle()
        val s = model.state.value
        assertEquals(false, s.visitorCommunity?.joined)
        assertEquals(10, s.visitorCommunity?.memberCount)
        assertFalse(s.isJoining)
        assertEquals("Only person accounts can join communities", s.errorMessage)
    }

    @Test
    fun joinGenerationGuardKeepsLocalMembershipOverAnInFlightFetch() = runTest(dispatcher) {
        val header = CompletableDeferred<CommunityProfile>()
        var communityCalls = 0
        val repo = FakeRepository().apply {
            community = {
                communityCalls += 1
                if (communityCalls == 1) detail else header.await()
            }
            posts = { page("p", 1) }
        }
        val model = visitor(repo) // joined = false, 10 members

        model.load() // second GET parks
        advanceUntilIdle()
        model.toggleJoin() // lands while that GET is in flight
        advanceUntilIdle()
        assertEquals(true, model.state.value.visitorCommunity?.joined)
        assertEquals(11, model.state.value.visitorCommunity?.memberCount)

        // The stale snapshot (pre-join) arrives: everything but membership is taken.
        header.complete(detail.copy(name = "TAT renamed", joined = false, memberCount = 10))
        advanceUntilIdle()
        val c = model.state.value.visitorCommunity
        assertEquals("TAT renamed", c?.name)
        assertEquals(true, c?.joined)
        assertEquals(11, c?.memberCount)
    }

    // endregion

    // region Votes, RSVPs, publish toggle

    @Test
    fun voteUpdatesGridAndOpenSheet() = runTest(dispatcher) {
        val options = listOf(PollOption("a", "A"), PollOption("b", "B"))
        val poll = post("poll1", kind = "poll").copy(poll = Poll(options, mapOf("a" to 1)))
        val updated = Poll(options, mapOf("a" to 1, "b" to 1))
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { listOf(poll, post("p2")) }
            voteResult = { VoteResult(poll = updated, myVote = "b") }
        }
        val model = visitor(repo)
        model.selectPost(poll)
        model.vote("poll1", "b")
        advanceUntilIdle()

        val s = model.state.value
        assertEquals("b", s.posts.first().myVote)
        assertEquals(updated, s.posts.first().poll)
        assertEquals("b", s.selectedPost?.myVote)
        assertEquals(updated, s.selectedPost?.poll)
        assertEquals("vote(poll1, b)", repo.calls.last())
    }

    @Test
    fun voteFailureLeavesPostUntouched() = runTest(dispatcher) {
        val poll = post("poll1", kind = "poll").copy(poll = Poll(listOf(PollOption("a", "A")), emptyMap()))
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { listOf(poll) }
            voteResult = { throw ApiError.Http(400, "Invalid option") }
        }
        val model = visitor(repo)
        model.selectPost(poll)
        model.vote("poll1", "zzz")
        advanceUntilIdle()
        assertEquals(poll, model.state.value.posts.single())
        assertEquals(poll, model.state.value.selectedPost)
        assertEquals("Invalid option", model.state.value.errorMessage)
    }

    @Test
    fun rsvpUpdatesAttendeesAndMyRsvp() = runTest(dispatcher) {
        val event = post("e1", kind = "event").copy(attendeeCount = 12, myRsvp = false)
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { listOf(event) }
            rsvpResult = { going -> RsvpResult(attendeeCount = if (going) 13 else 12, going = going) }
        }
        val model = visitor(repo)
        model.selectPost(event)

        model.rsvp("e1", going = true)
        advanceUntilIdle()
        assertEquals(13, model.state.value.posts.single().attendeeCount)
        assertEquals(true, model.state.value.selectedPost?.myRsvp)

        model.rsvp("e1", going = false)
        advanceUntilIdle()
        assertEquals(12, model.state.value.selectedPost?.attendeeCount)
        assertEquals(false, model.state.value.posts.single().myRsvp)
        assertEquals(listOf("rsvp(e1, true)", "rsvp(e1, false)"), repo.calls.filter { it.startsWith("rsvp") })
    }

    @Test
    fun selectingAnotherPostIsNotTouchedByUpdates() = runTest(dispatcher) {
        val event = post("e1", kind = "event").copy(attendeeCount = 1)
        val other = post("p2")
        val repo = FakeRepository().apply {
            community = { detail }
            posts = { listOf(event, other) }
            rsvpResult = { RsvpResult(attendeeCount = 2, going = true) }
        }
        val model = visitor(repo)
        model.selectPost(other)
        model.rsvp("e1", going = true)
        advanceUntilIdle()
        assertEquals(other, model.state.value.selectedPost)
        assertEquals(2, model.state.value.posts.first().attendeeCount)
    }

    @Test
    fun togglePublishPatchesClosesSheetAndReloads() = runTest(dispatcher) {
        var serverActive = true
        val repo = FakeRepository().apply {
            posts = { listOf(post("p1", active = serverActive)) }
        }
        val model = CommunityPageModel(cid, ownerMode = true, preview = null, repository = repo)
        advanceUntilIdle()
        val p1 = model.state.value.posts.single()
        model.selectPost(p1)

        serverActive = false
        model.togglePublish(p1)
        advanceUntilIdle()
        assertEquals("patch(p1, active=false)", repo.calls[1])
        assertNull(model.state.value.selectedPost)
        assertEquals(false, model.state.value.posts.single().active)
        assertEquals(2, repo.calls.count { it.startsWith("posts(") })

        // A missing `active` counts as published (`!(post.active ?? true)`).
        model.togglePublish(p1.copy(active = null))
        advanceUntilIdle()
        assertEquals(2, repo.calls.count { it == "patch(p1, active=false)" })
        model.togglePublish(p1.copy(active = false))
        advanceUntilIdle()
        assertEquals("patch(p1, active=true)", repo.calls.last { it.startsWith("patch") })
    }

    @Test
    fun togglePublishFailureKeepsSheetOpen() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            posts = { listOf(post("p1")) }
            patchError = ApiError.Http(404, "Post not found")
        }
        val model = CommunityPageModel(cid, ownerMode = true, preview = null, repository = repo)
        advanceUntilIdle()
        val p1 = model.state.value.posts.single()
        model.selectPost(p1)
        model.togglePublish(p1)
        advanceUntilIdle()
        assertEquals(p1, model.state.value.selectedPost)
        assertEquals("Post not found", model.state.value.errorMessage)
        assertEquals(1, repo.calls.count { it.startsWith("posts(") })
    }

    // endregion

    // region Safety

    @Test
    fun blockDismissesWithTheCommunityName() = runTest(dispatcher) {
        val repo = FakeRepository().apply { community = { detail } }
        val model = visitor(repo)
        val event = CompletableDeferred<CommunityPageEvent>()
        val collector = launch { event.complete(model.events.first()) }
        model.block()
        advanceUntilIdle()
        assertEquals(CommunityPageEvent.Dismiss, event.await())
        assertEquals("block($cid, TAT)", repo.calls.last())
        collector.cancel()
    }

    @Test
    fun blockFailureShowsErrorAndStays() = runTest(dispatcher) {
        val repo = FakeRepository().apply {
            community = { detail }
            blockError = ApiError.Http(500, "Server error")
        }
        val model = visitor(repo)
        var dismissed = false
        val collector = launch { model.events.collect { dismissed = true } }
        model.block()
        advanceUntilIdle()
        assertFalse(dismissed)
        assertEquals("Server error", model.state.value.errorMessage)
        collector.cancel()
    }

    @Test
    fun reportSendsReasonAndSurfacesFailures() = runTest(dispatcher) {
        val repo = FakeRepository().apply { community = { detail } }
        val model = visitor(repo)
        model.report("Spam")
        advanceUntilIdle()
        assertEquals("report($cid, Spam)", repo.calls.last())
        assertNull(model.state.value.errorMessage)

        repo.reportError = ApiError.Http(429, "Too many reports")
        model.report("Spam")
        advanceUntilIdle()
        assertEquals("Too many reports", model.state.value.errorMessage)
    }

    // endregion
}
