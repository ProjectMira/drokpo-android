package app.drokpo.android.features.shared.sharing

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.Poll
import app.drokpo.android.core.model.PollOption
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.VoteResult
import app.drokpo.android.navigation.SharedRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
class SharedContentModelsTest {
    @get:Rule val main = SharingMainDispatcherRule()

    private val poll = Poll(
        options = listOf(PollOption("a", "Saturday"), PollOption("b", "Sunday")),
        counts = mapOf("a" to 2, "b" to 1),
    )
    private val pollPost = CommunityPostCard(postId = "p1", kind = "poll", poll = poll, communityName = "TAT")
    private val eventPost = CommunityPostCard(postId = "p2", kind = "event", attendeeCount = 12, myRsvp = false)

    /** Vote / RSVP run here (production: AppGraph.appScope), on the test clock. */
    private fun postModel(postId: String, api: SharedContentApi) =
        SharedPostModel(postId, api, workScope = CoroutineScope(SupervisorJob() + main.dispatcher))

    /** Records calls; each answer can be swapped per test. */
    private class FakeApi(
        var post: suspend (String) -> CommunityPostCard = { error("unused") },
        var vote: suspend (String, String) -> VoteResult = { _, _ -> error("unused") },
        var rsvp: suspend (String, Boolean) -> RsvpResult = { _, _ -> error("unused") },
    ) : SharedContentApi {
        val calls = mutableListOf<String>()
        override suspend fun user(uid: String): FeedCard = error("unused")
        override suspend fun post(postId: String): CommunityPostCard = post.invoke(postId).also { calls += "post $postId" }
        override suspend fun news(newsId: String): NewsCard = error("unused")
        override suspend fun vote(postId: String, optionId: String): VoteResult =
            vote.invoke(postId, optionId).also { calls += "vote $postId $optionId" }
        override suspend fun rsvp(postId: String, going: Boolean): RsvpResult =
            rsvp.invoke(postId, going).also { calls += "rsvp $postId $going" }
    }

    // ------------------------------------------------------------------ loaders

    @Test fun contentLoaderLoadsOnce() = runTest(main.dispatcher) {
        var fetches = 0
        val model = SharedContentModel { fetches++; FeedCard(uid = "u1", displayName = "Pema") }
        assertEquals(SharedLoad.Loading, model.state.value)
        advanceUntilIdle()
        assertEquals(SharedLoad.Loaded(FeedCard(uid = "u1", displayName = "Pema")), model.state.value)
        assertEquals(1, fetches)
    }

    @Test fun contentLoaderFailsOnAnyError() = runTest(main.dispatcher) {
        val notFound = SharedContentModel<NewsCard> { throw ApiError.Http(404, "Not found") }
        val offline = SharedContentModel<NewsCard> { throw UnknownHostException() }
        advanceUntilIdle()
        assertEquals(SharedLoad.Failed, notFound.state.value)
        assertEquals(SharedLoad.Failed, offline.state.value)
    }

    @Test fun postLoaderFetchesById() = runTest(main.dispatcher) {
        val api = FakeApi(post = { pollPost })
        val model = postModel("p1", api)
        advanceUntilIdle()
        assertEquals(SharedLoad.Loaded(pollPost), model.state.value)
        assertEquals(listOf("post p1"), api.calls)
    }

    @Test fun postLoaderFailure() = runTest(main.dispatcher) {
        val model = postModel("gone", FakeApi(post = { throw ApiError.Http(404, "Post not found") }))
        advanceUntilIdle()
        assertEquals(SharedLoad.Failed, model.state.value)
        // A load failure is the "Content unavailable" view, never the alert.
        assertNull(model.errorMessage.value)
    }

    // ------------------------------------------------------------------ live post

    @Test fun voteAppliesServerResult() = runTest(main.dispatcher) {
        val updated = poll.copy(counts = mapOf("a" to 2, "b" to 2))
        val api = FakeApi(post = { pollPost }, vote = { _, _ -> VoteResult(poll = updated, myVote = "b") })
        val model = postModel("p1", api)
        advanceUntilIdle()

        model.vote("b")
        advanceUntilIdle()
        val post = (model.state.value as SharedLoad.Loaded).value
        assertEquals(updated, post.poll)
        assertEquals("b", post.myVote)
        assertEquals(listOf("post p1", "vote p1 b"), api.calls)
    }

    @Test fun voteFailureKeepsPostAndAlerts() = runTest(main.dispatcher) {
        val api = FakeApi(post = { pollPost }, vote = { _, _ -> throw ApiError.Http(400, "Poll is closed") })
        val model = postModel("p1", api)
        advanceUntilIdle()

        model.vote("a")
        advanceUntilIdle()
        assertEquals(SharedLoad.Loaded(pollPost), model.state.value)
        assertEquals("Poll is closed", model.errorMessage.value)
        model.dismissError()
        assertNull(model.errorMessage.value)
    }

    @Test fun rsvpGoingAndNotGoing() = runTest(main.dispatcher) {
        val api = FakeApi(
            post = { eventPost },
            rsvp = { _, going -> RsvpResult(attendeeCount = if (going) 13 else 12, going = going) },
        )
        val model = postModel("p2", api)
        advanceUntilIdle()

        model.rsvp(true)
        advanceUntilIdle()
        var post = (model.state.value as SharedLoad.Loaded).value
        assertEquals(13, post.attendeeCount)
        assertEquals(true, post.myRsvp)

        model.rsvp(false)
        advanceUntilIdle()
        post = (model.state.value as SharedLoad.Loaded).value
        assertEquals(12, post.attendeeCount)
        assertEquals(false, post.myRsvp)
        assertEquals(listOf("post p2", "rsvp p2 true", "rsvp p2 false"), api.calls)
    }

    @Test fun rsvpFailureAlerts() = runTest(main.dispatcher) {
        val api = FakeApi(post = { eventPost }, rsvp = { _, _ -> throw UnknownHostException() })
        val model = postModel("p2", api)
        advanceUntilIdle()
        model.rsvp(true)
        advanceUntilIdle()
        assertEquals(SharedLoad.Loaded(eventPost), model.state.value)
        assertEquals("The Internet connection appears to be offline.", model.errorMessage.value)
    }

    @Test fun voteAndRsvpUseTheLoadedPostsId() = runTest(main.dispatcher) {
        // iOS posts to /api/posts/\(post.postId)/… — the fetched post's id, not the link's.
        val api = FakeApi(
            post = { pollPost },
            vote = { _, _ -> VoteResult(poll = poll, myVote = "a") },
            rsvp = { _, going -> RsvpResult(attendeeCount = 1, going = going) },
        )
        val model = postModel("p1-link-alias", api)
        advanceUntilIdle()
        model.vote("a")
        model.rsvp(true)
        advanceUntilIdle()
        assertEquals(listOf("post p1-link-alias", "vote p1 a", "rsvp p1 true"), api.calls)
    }

    @Test fun voteBeforeTheLoadIsIgnored() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<CommunityPostCard>()
        val api = FakeApi(post = { gate.await() }, vote = { _, _ -> error("must not vote") })
        val model = postModel("p1", api)
        model.vote("a")
        model.rsvp(true)
        gate.complete(pollPost)
        advanceUntilIdle()
        assertEquals(listOf("post p1"), api.calls)
        assertNull(model.errorMessage.value)
    }

    @Test fun voteSurvivesClosingTheSheet() = runTest(main.dispatcher) {
        // Closing the sheet clears its ViewModelStore; the in-flight vote must still reach the server.
        val gate = CompletableDeferred<VoteResult>()
        val api = FakeApi(post = { pollPost }, vote = { _, _ -> gate.await() })
        val store = ViewModelStore()
        val model = ViewModelProvider.create(store, viewModelFactory { initializer { postModel("p1", api) } })[SharedPostModel::class]
        advanceUntilIdle()

        model.vote("b")
        runCurrent()
        store.clear()
        gate.complete(VoteResult(poll = poll, myVote = "b"))
        advanceUntilIdle()
        assertEquals(listOf("post p1", "vote p1 b"), api.calls)
    }

    // ------------------------------------------------------------------ routing

    @Test fun startRoutePerDestination() {
        assertEquals(SharedRoute.Community(cid = "c1"), startRoute(ShareDestination.Community("c1")))
        assertEquals(ShareRoute.UserLoader("u1"), startRoute(ShareDestination.User("u1")))
        assertEquals(ShareRoute.PostLoader("p1"), startRoute(ShareDestination.Post("p1")))
        assertEquals(ShareRoute.NewsLoader("n1"), startRoute(ShareDestination.News("n1")))
    }
}
