package app.drokpo.android.features.likes

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.LikedContent
import app.drokpo.android.core.model.Match
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.SwipeEntry
import app.drokpo.android.core.model.SwipeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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
class LikesModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region Fixtures & fakes

    private val yangchen = FeedCard(uid = "u-yangchen", displayName = "Yangchen", age = 29)
    private val dechen = FeedCard(uid = "u-dechen", displayName = "Dechen")
    private val nameless = FeedCard(uid = "u-nameless")
    private val pema = FeedCard(uid = "u-pema", displayName = "Pema")

    private fun like(card: FeedCard, matchId: String? = null, createdAt: String? = "2026-10-06T10:00:00Z") =
        SwipeEntry(uid = card.uid, action = "like", createdAt = createdAt, otherUser = card, matchId = matchId)

    private val news = NewsCard(newsId = "n-losar", title = "Losar", sourceUrl = "https://example.org/losar")
    private val post = CommunityPostCard(postId = "p-event", kind = "event", title = "Losar party")
    private val savedNews = LikedContent.News(news, "2026-10-07T08:00:00Z")
    private val savedPost = LikedContent.Post(post, "2026-10-06T12:00:00Z")

    private class FakeRepository : LikesRepository {
        var received: suspend () -> List<SwipeEntry> = { emptyList() }
        var given: suspend () -> List<SwipeEntry> = { emptyList() }
        var content: suspend () -> List<LikedContent> = { emptyList() }
        var like: suspend (String) -> SwipeResult = { SwipeResult(matched = false) }
        var unlikeNewsCall: suspend (String) -> Unit = {}
        var unlikePostCall: suspend (String) -> Unit = {}
        val calls = mutableListOf<String>()

        override suspend fun receivedLikes(): List<SwipeEntry> = received()
        override suspend fun givenLikes(): List<SwipeEntry> = given()
        override suspend fun likedContent(): List<LikedContent> = content()

        override suspend fun likeBack(uid: String): SwipeResult {
            calls += "like:$uid"
            return like(uid)
        }

        override suspend fun unlikeNews(newsId: String) {
            calls += "unlikeNews:$newsId"
            unlikeNewsCall(newsId)
        }

        override suspend fun unlikePost(postId: String) {
            calls += "unlikePost:$postId"
            unlikePostCall(postId)
        }
    }

    private class Harness {
        val repository = FakeRepository()
        val focus = MutableStateFlow(false)
        val openedThreads = mutableListOf<String>()
        val model = LikesModel(repository, focus) { openedThreads += it }
    }

    /** A model that has finished one successful load with [received], [given] and [content]. */
    private fun TestScope.loaded(
        received: List<SwipeEntry> = listOf(like(yangchen), like(dechen)),
        given: List<SwipeEntry> = listOf(like(pema)),
        content: List<LikedContent> = listOf(savedNews, savedPost),
    ): Harness = Harness().apply {
        repository.received = { received }
        repository.given = { given }
        repository.content = { content }
        model.load()
        advanceUntilIdle()
    }

    // endregion

    // region Defaults, segment and filter

    @Test
    fun startsOnYouLikedAllWithTheSpinner() {
        val state = Harness().model.state.value
        assertEquals(Direction.Given, state.direction)
        assertEquals(GivenFilter.All, state.givenFilter)
        assertTrue(state.isLoading)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun selectingSegmentAndFilterUpdatesState() {
        val model = Harness().model
        model.selectDirection(Direction.Received)
        model.selectFilter(GivenFilter.News)
        assertEquals(Direction.Received, model.state.value.direction)
        assertEquals(GivenFilter.News, model.state.value.givenFilter)
    }

    // endregion

    // region Like push

    @Test
    fun likePushFlagOpensLikedYouAndIsCleared() {
        val h = Harness()
        h.focus.value = true
        h.model.consumeLikePush()
        assertFalse(h.focus.value)
        assertEquals(Direction.Received, h.model.state.value.direction)
    }

    @Test
    fun withoutTheFlagTheSegmentIsLeftAlone() {
        val h = Harness()
        h.model.consumeLikePush()
        assertEquals(Direction.Given, h.model.state.value.direction)
    }

    @Test
    fun appearingConsumesTheFlagAndLoads() = runTest(dispatcher) {
        val h = Harness()
        h.repository.received = { listOf(like(yangchen)) }
        h.focus.value = true
        h.model.onAppear()
        advanceUntilIdle()
        assertEquals(Direction.Received, h.model.state.value.direction)
        assertFalse(h.focus.value)
        assertEquals(listOf("u-yangchen"), h.model.state.value.received.map { it.id })
    }

    // endregion

    // region Loading

    @Test
    fun loadFillsAllThreeListsAndDropsMatchedPeople() = runTest(dispatcher) {
        val h = loaded(
            received = listOf(like(yangchen), like(dechen, matchId = "m-dechen")),
            given = listOf(like(pema), like(nameless, matchId = "m-1")),
        )
        val state = h.model.state.value
        assertEquals(listOf("u-yangchen"), state.received.map { it.id })
        assertEquals(listOf("u-pema"), state.given.map { it.id })
        assertEquals(listOf(savedNews, savedPost), state.likedContent)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun theSpinnerOnlyShowsWhileEverythingIsEmpty() = runTest(dispatcher) {
        val h = loaded()
        val gate = CompletableDeferred<List<SwipeEntry>>()
        h.repository.received = { gate.await() }
        h.model.load()
        advanceUntilIdle()
        // A reload with data on screen is silent.
        assertFalse(h.model.state.value.isLoading)
        gate.complete(emptyList())
        advanceUntilIdle()

        // Nothing at all to show → the full spinner again until the load finishes.
        val empty = Harness()
        val emptyGate = CompletableDeferred<List<SwipeEntry>>()
        empty.repository.received = { emptyGate.await() }
        empty.model.load()
        advanceUntilIdle()
        assertTrue(empty.model.state.value.isLoading)
        emptyGate.complete(emptyList())
        advanceUntilIdle()
        assertFalse(empty.model.state.value.isLoading)
    }

    @Test
    fun aFailedLoadKeepsWhatWasAppliedAndShowsTheError() = runTest(dispatcher) {
        // iOS assigns received, then given, then content: a given failure keeps the new
        // received list but leaves given and content as they were.
        val h = loaded(received = listOf(like(dechen)), given = listOf(like(pema)), content = listOf(savedPost))
        h.repository.received = { listOf(like(yangchen)) }
        h.repository.given = { throw ApiError.Http(500, "Server exploded") }
        h.repository.content = { listOf(savedNews) }
        h.model.load()
        advanceUntilIdle()
        val state = h.model.state.value
        assertEquals(listOf("u-yangchen"), state.received.map { it.id })
        assertEquals(listOf("u-pema"), state.given.map { it.id })
        assertEquals(listOf(savedPost), state.likedContent)
        assertEquals("Server exploded", state.errorMessage)
        assertFalse(state.isLoading)
    }

    @Test
    fun aFirstLoadFailureEndsOnTheEmptyStateWithAnAlert() = runTest(dispatcher) {
        val h = Harness()
        h.repository.received = { throw ApiError.NotAuthenticated }
        h.repository.given = { listOf(like(pema)) }
        h.model.load()
        advanceUntilIdle()
        val state = h.model.state.value
        assertTrue(state.received.isEmpty())
        assertTrue(state.given.isEmpty())
        assertFalse(state.isLoading)
        assertEquals("You need to sign in again.", state.errorMessage)
    }

    @Test
    fun missingContentItemsMeanNoSavedContent() = runTest(dispatcher) {
        val h = loaded(content = emptyList())
        assertTrue(h.model.state.value.likedContent.isEmpty())
    }

    @Test
    fun pullToRefreshRaisesTheIndicatorUntilTheLoadEnds() = runTest(dispatcher) {
        val h = loaded()
        val gate = CompletableDeferred<List<LikedContent>>()
        h.repository.content = { gate.await() }
        h.model.refresh()
        advanceUntilIdle()
        assertTrue(h.model.state.value.isRefreshing)
        assertFalse(h.model.state.value.isLoading)
        gate.complete(listOf(savedPost))
        advanceUntilIdle()
        assertFalse(h.model.state.value.isRefreshing)
        assertEquals(listOf(savedPost), h.model.state.value.likedContent)
    }

    @Test
    fun aNewerLoadWinsOverOneStillInFlight() = runTest(dispatcher) {
        val h = Harness()
        val slow = CompletableDeferred<List<SwipeEntry>>()
        h.repository.received = { slow.await() }
        h.model.load()
        advanceUntilIdle()

        h.repository.received = { listOf(like(dechen)) }
        h.model.load()
        advanceUntilIdle()
        slow.complete(listOf(like(yangchen)))
        advanceUntilIdle()

        assertEquals(listOf("u-dechen"), h.model.state.value.received.map { it.id })
        assertNull(h.model.state.value.errorMessage)
        assertFalse(h.model.state.value.isLoading)
    }

    // endregion

    // region Like back

    @Test
    fun likeBackRemovesTheRowAndReturnsTheResultWithoutItsOwnAlert() = runTest(dispatcher) {
        val h = loaded()
        h.repository.like = { SwipeResult(matched = true, matchId = "m-1") }
        val result = h.model.likeBack(yangchen)
        assertEquals("m-1", result?.matchId)
        assertEquals(listOf("like:u-yangchen"), h.repository.calls)
        assertEquals(listOf("u-dechen"), h.model.state.value.received.map { it.id })
        // ProfileDetailScreen shows the match alert for this path — Likes must not.
        assertNull(h.model.state.value.matched)
    }

    @Test
    fun aFailedLikeBackKeepsTheRowAndShowsTheError() = runTest(dispatcher) {
        val h = loaded()
        h.repository.like = { throw ApiError.Http(403, "Community must be verified to like people") }
        assertNull(h.model.likeBack(yangchen))
        assertEquals(2, h.model.state.value.received.size)
        assertEquals("Community must be verified to like people", h.model.state.value.errorMessage)
    }

    @Test
    fun likeBackStillLandsWhenTheProfileThatAskedIsGone() = runTest(dispatcher) {
        val h = loaded()
        val gate = CompletableDeferred<SwipeResult>()
        h.repository.like = { gate.await() }
        val caller = launch { h.model.likeBack(yangchen) }
        advanceUntilIdle()
        caller.cancel()
        gate.complete(SwipeResult(matched = true, matchId = "m-1"))
        advanceUntilIdle()
        assertEquals(listOf("u-dechen"), h.model.state.value.received.map { it.id })
    }

    @Test
    fun rowHeartMatchShowsTheAlert() = runTest(dispatcher) {
        val h = loaded()
        h.repository.like = { SwipeResult(matched = true, matchId = "m-yangchen") }
        h.model.likeBackFromRow(yangchen)
        advanceUntilIdle()
        assertEquals(MatchedAlert("Yangchen", "m-yangchen"), h.model.state.value.matched)
        assertEquals(listOf("u-dechen"), h.model.state.value.received.map { it.id })
    }

    @Test
    fun matchAlertFallsBackToTheEmbeddedMatchAndTheyForTheName() = runTest(dispatcher) {
        val h = loaded(received = listOf(like(nameless)))
        h.repository.like = { SwipeResult(match = Match(matchId = "m-embedded")) }
        h.model.likeBackFromRow(nameless)
        advanceUntilIdle()
        assertEquals(MatchedAlert("they", "m-embedded"), h.model.state.value.matched)
    }

    @Test
    fun rowHeartWithoutAMatchJustRemovesTheRow() = runTest(dispatcher) {
        val h = loaded()
        h.repository.like = { SwipeResult(matched = false) }
        h.model.likeBackFromRow(yangchen)
        advanceUntilIdle()
        assertNull(h.model.state.value.matched)
        assertEquals(listOf("u-dechen"), h.model.state.value.received.map { it.id })
    }

    @Test
    fun aSecondTapOnTheSameHeartIsIgnoredWhileTheFirstRuns() = runTest(dispatcher) {
        val h = loaded()
        val gate = CompletableDeferred<SwipeResult>()
        h.repository.like = { gate.await() }
        h.model.likeBackFromRow(yangchen)
        h.model.likeBackFromRow(yangchen)
        advanceUntilIdle()
        gate.complete(SwipeResult(matched = false))
        advanceUntilIdle()
        assertEquals(listOf("like:u-yangchen"), h.repository.calls)

        // Once it's done, the heart works again (e.g. after a failure).
        h.repository.like = { SwipeResult(matched = false) }
        h.model.likeBackFromRow(dechen)
        advanceUntilIdle()
        assertEquals(listOf("like:u-yangchen", "like:u-dechen"), h.repository.calls)
    }

    @Test
    fun sayHiOpensTheThreadAndAlertsDismiss() = runTest(dispatcher) {
        val h = loaded()
        h.repository.like = { SwipeResult(matched = true, matchId = "m-yangchen") }
        h.model.likeBackFromRow(yangchen)
        advanceUntilIdle()
        val matchId = h.model.state.value.matched?.matchId
        // DrokpoAlert dismisses first, then runs the button.
        h.model.dismissMatch()
        h.model.sayHi(matchId)
        assertNull(h.model.state.value.matched)
        assertEquals(listOf("m-yangchen"), h.openedThreads)

        h.model.sayHi(null)
        assertEquals(listOf("m-yangchen"), h.openedThreads)
    }

    @Test
    fun dismissErrorClearsIt() = runTest(dispatcher) {
        val h = Harness()
        h.repository.received = { throw ApiError.InvalidResponse }
        h.model.load()
        advanceUntilIdle()
        assertEquals("Unexpected response from the server.", h.model.state.value.errorMessage)
        h.model.dismissError()
        assertNull(h.model.state.value.errorMessage)
    }

    // endregion

    // region Remove saved content

    @Test
    fun removingASavedStoryDeletesItThenDropsTheRow() = runTest(dispatcher) {
        val h = loaded()
        assertTrue(h.model.unlikeNews(news))
        assertEquals(listOf("unlikeNews:n-losar"), h.repository.calls)
        assertEquals(listOf(savedPost), h.model.state.value.likedContent)
    }

    @Test
    fun removingASavedPostDeletesItThenDropsTheRow() = runTest(dispatcher) {
        val h = loaded()
        assertTrue(h.model.unlikePost(post))
        assertEquals(listOf("unlikePost:p-event"), h.repository.calls)
        assertEquals(listOf(savedNews), h.model.state.value.likedContent)
    }

    @Test
    fun theRowStaysUntilTheDeleteSucceeds() = runTest(dispatcher) {
        val h = loaded()
        val gate = CompletableDeferred<Unit>()
        h.repository.unlikeNewsCall = { gate.await() }
        val removal = async { h.model.unlikeNews(news) }
        advanceUntilIdle()
        // Not optimistic (iOS awaits the DELETE before touching the list).
        assertEquals(listOf(savedNews, savedPost), h.model.state.value.likedContent)
        gate.complete(Unit)
        assertTrue(removal.await())
        assertEquals(listOf(savedPost), h.model.state.value.likedContent)
    }

    @Test
    fun aFailedRemoveKeepsTheRowAndShowsTheError() = runTest(dispatcher) {
        val h = loaded()
        h.repository.unlikePostCall = { throw ApiError.Http(500, "") }
        assertFalse(h.model.unlikePost(post))
        assertEquals(listOf(savedNews, savedPost), h.model.state.value.likedContent)
        assertEquals("Server error (500).", h.model.state.value.errorMessage)

        // The failed request doesn't stick: a retry asks the server again.
        h.repository.unlikePostCall = {}
        assertTrue(h.model.unlikePost(post))
        assertEquals(listOf("unlikePost:p-event", "unlikePost:p-event"), h.repository.calls)
    }

    @Test
    fun aRepeatedRemoveJoinsTheRequestInFlight() = runTest(dispatcher) {
        val h = loaded()
        val gate = CompletableDeferred<Unit>()
        h.repository.unlikeNewsCall = { gate.await() }
        val first = async { h.model.unlikeNews(news) }
        val second = async { h.model.unlikeNews(news) }
        advanceUntilIdle()
        gate.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(listOf("unlikeNews:n-losar"), h.repository.calls)
    }

    @Test
    fun aRemoveStillAppliesWhenTheRowThatAskedIsGone() = runTest(dispatcher) {
        val h = loaded()
        val gate = CompletableDeferred<Unit>()
        h.repository.unlikeNewsCall = { gate.await() }
        val row = launch { h.model.unlikeNews(news) }
        advanceUntilIdle()
        row.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(savedPost), h.model.state.value.likedContent)
    }

    // endregion
}
