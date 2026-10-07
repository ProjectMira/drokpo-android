package app.drokpo.android.features.feed

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.AdCard
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.FeedItem
import app.drokpo.android.core.model.FeedPage
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.Poll
import app.drokpo.android.core.model.PollOption
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.SwipeAction
import app.drokpo.android.core.model.SwipeResult
import app.drokpo.android.core.model.VoteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class FeedModelTest {
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

    private fun person(n: Int) = FeedCard(uid = "u$n", displayName = "Person $n")
    private fun ad(id: String, link: String? = "https://example.com/$id") = AdCard(adId = id, title = "Ad $id", linkUrl = link)
    private fun news(id: String) = NewsCard(newsId = id, title = "News $id", sourceUrl = "https://news.example/$id")
    private fun post(id: String, kind: String = "announcement", link: String? = null) =
        CommunityPostCard(postId = id, kind = kind, title = "Post $id", linkUrl = link)

    private fun persons(vararg n: Int): List<FeedItem> = n.map { FeedItem.Person(person(it)) }
    private fun itemsPage(items: List<FeedItem>) = FeedPage(items = items)

    private class FakeFeedApi : FeedApi {
        /** Pages served in order; once empty, an empty server-ordered page. */
        val pages = ArrayDeque<FeedPage>()
        var fetchCount = 0
        var fetchGate: CompletableDeferred<Unit>? = null
        var fetchError: Exception? = null

        val calls = mutableListOf<String>()
        var swipeResult: SwipeResult = SwipeResult(matched = false)
        val swipeGates = mutableMapOf<String, CompletableDeferred<SwipeResult>>()
        var swipeError: Exception? = null
        var undoError: Exception? = null
        var voteResult = VoteResult()
        var voteError: Exception? = null
        var rsvpResult = RsvpResult(attendeeCount = 1, going = true)
        var reportError: Exception? = null
        var blockError: Exception? = null

        override suspend fun fetchPage(): FeedPage {
            fetchCount += 1
            fetchGate?.await()
            fetchError?.let { throw it }
            return pages.removeFirstOrNull() ?: FeedPage(items = emptyList())
        }

        override suspend fun swipe(uid: String, action: SwipeAction): SwipeResult {
            calls += "swipe $uid ${action.rawValue}"
            swipeGates[uid]?.let { return it.await() }
            swipeError?.let { throw it }
            return swipeResult
        }

        override suspend fun undoSwipe(uid: String) {
            calls += "undo $uid"
            undoError?.let { throw it }
        }

        override suspend fun saveNews(newsId: String) {
            calls += "saveNews $newsId"
        }

        override suspend fun savePost(postId: String) {
            calls += "savePost $postId"
        }

        override suspend fun vote(postId: String, optionId: String): VoteResult {
            calls += "vote $postId $optionId"
            voteError?.let { throw it }
            return voteResult
        }

        override suspend fun rsvp(postId: String, going: Boolean): RsvpResult {
            calls += "rsvp $postId $going"
            return rsvpResult
        }

        override suspend fun report(uid: String, reason: String) {
            calls += "report $uid $reason"
            reportError?.let { throw it }
        }

        override suspend fun block(uid: String, displayName: String?) {
            calls += "block $uid $displayName"
            blockError?.let { throw it }
        }
    }

    private val events = mutableListOf<String>()

    private fun model(api: FeedApi) = FeedModel(api = api, sendContentEvent = { path, event -> events += "$event $path" })

    private val FeedModel.ids: List<String> get() = state.value.deck.map { it.id }

    /** A model whose deck holds [items] (server-ordered), fully loaded. */
    private fun TestScope.loaded(api: FakeFeedApi, items: List<FeedItem>): FeedModel {
        api.pages += itemsPage(items)
        val model = model(api)
        model.loadInitial()
        advanceUntilIdle()
        return model
    }

    // endregion

    // region Loading

    @Test
    fun spinnerOnlyForTheInitialLoad() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val gate = CompletableDeferred<Unit>()
        api.fetchGate = gate
        api.pages += itemsPage(persons(1, 2))
        val model = model(api)

        model.loadInitial()
        runCurrent()
        assertTrue(model.state.value.isLoading)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.isLoading)
        assertEquals(listOf("profile-u1", "profile-u2"), model.ids)

        // Re-entering the tab with cards in the deck doesn't fetch again.
        model.loadInitial()
        advanceUntilIdle()
        assertEquals(1, api.fetchCount)
    }

    @Test
    fun reEnteringTheTabWhileLoadingDoesNotEndTheSpinnerEarly() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val gate = CompletableDeferred<Unit>()
        api.fetchGate = gate
        val model = model(api)

        model.loadInitial()
        runCurrent()
        model.loadInitial()
        runCurrent()
        assertTrue(model.state.value.isLoading)
        assertEquals(1, api.fetchCount)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun emptyDeckRefreshFetchesWithoutSpinner() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, emptyList())
        assertTrue(model.state.value.deck.isEmpty())

        api.pages += itemsPage(persons(1))
        model.refresh()
        runCurrent()
        assertFalse(model.state.value.isLoading)
        advanceUntilIdle()
        assertEquals(listOf("profile-u1"), model.ids)
        assertEquals(2, api.fetchCount)
    }

    @Test
    fun fetchRequestsAreNeverConcurrent() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val gate = CompletableDeferred<Unit>()
        api.fetchGate = gate
        val model = model(api)

        model.refresh()
        model.refresh()
        model.refresh()
        runCurrent()
        assertEquals(1, api.fetchCount)

        gate.complete(Unit)
        advanceUntilIdle()
        model.refresh()
        advanceUntilIdle()
        assertEquals(2, api.fetchCount)
    }

    @Test
    fun fetchFailureSurfacesTheServerMessage() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.fetchError = ApiError.Http(status = 400, message = "Complete onboarding before viewing the feed")
        val model = model(api)
        model.loadInitial()
        advanceUntilIdle()

        assertEquals("Complete onboarding before viewing the feed", model.state.value.errorMessage)
        assertFalse(model.state.value.isLoading)
        model.clearError()
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun serverOrderedPagesDropDuplicatesSwipedAndSavedCards() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(
            api,
            listOf(
                FeedItem.Person(person(1)),
                FeedItem.News(news("n1")),
                FeedItem.Person(person(2)),
                FeedItem.Ad(ad("a1")),
                FeedItem.Person(person(3)),
                FeedItem.Person(person(4)),
                FeedItem.Person(person(5)),
            ),
        )
        assertEquals(
            listOf("profile-u1", "news-n1", "profile-u2", "ad-a1", "profile-u3", "profile-u4", "profile-u5"),
            model.ids,
        )

        model.swipe(person(1), SwipeAction.pass) // swiped this session
        model.swipeNews(news("n1"), liked = true) // saved this session
        advanceUntilIdle()

        api.pages += itemsPage(
            listOf(
                FeedItem.Person(person(1)), // swiped — the race the iOS comment describes
                FeedItem.News(news("n1")), // saved
                FeedItem.Person(person(2)), // already in the deck
                FeedItem.Ad(ad("a1")), // already in the deck
                FeedItem.Post(post("p1")),
                FeedItem.Person(person(6)),
                FeedItem.Person(person(6)), // duplicate within the page
            ),
        )
        model.refresh()
        advanceUntilIdle()
        assertEquals(
            listOf("profile-u2", "ad-a1", "profile-u3", "profile-u4", "profile-u5", "post-p1", "profile-u6"),
            model.ids,
        )
    }

    // endregion

    // region Legacy client-side mixing

    @Test
    fun legacyMixingInsertsOneContentCardAfterEveryThreeProfilesCyclingAdNewsPost() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.pages += FeedPage(
            candidates = (1..7).map(::person),
            ads = listOf(ad("a1")),
            news = listOf(news("n1")),
            communityPosts = listOf(post("p1")),
        )
        val model = model(api)
        model.loadInitial()
        advanceUntilIdle()
        assertEquals(
            listOf(
                "profile-u1", "profile-u2", "profile-u3", "ad-a1",
                "profile-u4", "profile-u5", "profile-u6", "news-n1",
                "profile-u7",
            ),
            model.ids,
        )

        api.pages += FeedPage(
            candidates = (8..9).map(::person),
            ads = listOf(ad("a1")),
            news = listOf(news("n1")),
            communityPosts = listOf(post("p1")),
        )
        model.refresh()
        advanceUntilIdle()
        // u7 + u8 + u9 make three since the last content card → the post queue is next.
        assertEquals(listOf("profile-u7", "profile-u8", "profile-u9", "post-p1"), model.ids.takeLast(4))
    }

    @Test
    fun legacyMixingSkipsEmptyQueuesAndContentAlreadyShowing() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.pages += FeedPage(candidates = (1..7).map(::person), ads = listOf(ad("a1")))
        val model = model(api)
        model.loadInitial()
        advanceUntilIdle()
        // a1 is still in the deck at u6, and news/posts are empty: no second content card.
        assertEquals(
            listOf("profile-u1", "profile-u2", "profile-u3", "ad-a1", "profile-u4", "profile-u5", "profile-u6", "profile-u7"),
            model.ids,
        )
    }

    @Test
    fun legacyCandidatesAlreadyInTheDeckOrSwipedAreFiltered() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.pages += FeedPage(candidates = (1..5).map(::person))
        val model = model(api)
        model.loadInitial()
        advanceUntilIdle()
        model.swipe(person(1), SwipeAction.like)
        advanceUntilIdle()

        api.pages += FeedPage(candidates = listOf(person(1), person(2), person(6)))
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf("profile-u2", "profile-u3", "profile-u4", "profile-u5", "profile-u6"), model.ids)
    }

    // endregion

    // region Profile swipes & undo

    @Test
    fun profileSwipeRemovesTheCardAtOnceThenRecordsIt() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, persons(1, 2, 3, 4, 5, 6))

        model.swipe(person(1), SwipeAction.like)
        assertEquals("profile-u2", model.ids.first())
        assertTrue(model.state.value.canUndo)
        assertEquals(person(1), model.state.value.lastSwipedProfile)
        assertTrue(api.calls.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf("swipe u1 like"), api.calls)
        assertNull(model.state.value.matchedCard)
        assertTrue(model.state.value.canUndo)
    }

    @Test
    fun matchShowsTheOverlayAndDropsUndo() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.swipeResult = SwipeResult(matched = true, matchId = "m1")
        val model = loaded(api, persons(1, 2, 3, 4, 5))

        model.swipe(person(1), SwipeAction.like)
        advanceUntilIdle()
        assertEquals(person(1), model.state.value.matchedCard)
        // A match can't be undone (the server refuses).
        assertFalse(model.state.value.canUndo)

        model.dismissMatch()
        assertNull(model.state.value.matchedCard)
    }

    @Test
    fun aPassNeverShowsTheMatchOverlay() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.swipeResult = SwipeResult(matched = true)
        val model = loaded(api, persons(1, 2, 3, 4, 5))
        model.swipe(person(1), SwipeAction.pass)
        advanceUntilIdle()
        assertNull(model.state.value.matchedCard)
        assertTrue(model.state.value.canUndo)
    }

    @Test
    fun aLateMatchOnAnEarlierSwipeKeepsUndoForTheLatestOne() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val first = CompletableDeferred<SwipeResult>()
        api.swipeGates["u1"] = first
        val model = loaded(api, persons(1, 2, 3, 4, 5, 6))

        model.swipe(person(1), SwipeAction.like)
        runCurrent()
        model.swipe(person(2), SwipeAction.like)
        advanceUntilIdle()
        first.complete(SwipeResult(matched = true))
        advanceUntilIdle()

        assertEquals(person(1), model.state.value.matchedCard)
        assertEquals(person(2), model.state.value.lastSwipedProfile)
    }

    @Test
    fun swipeFailureShowsAnAlert() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.swipeError = ApiError.Http(status = 403, message = "Only verified communities can like people")
        val model = loaded(api, persons(1, 2, 3, 4, 5))
        model.swipe(person(1), SwipeAction.like)
        advanceUntilIdle()
        assertEquals("Only verified communities can like people", model.state.value.errorMessage)
        assertEquals("profile-u2", model.ids.first())
    }

    @Test
    fun swipingDownToThreeCardsRefills() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, persons(1, 2, 3, 4, 5))
        api.pages += itemsPage(persons(6, 7))

        model.swipe(person(1), SwipeAction.pass) // 4 left
        advanceUntilIdle()
        assertEquals(1, api.fetchCount)

        model.swipe(person(2), SwipeAction.pass) // 3 left → refill after the POST
        advanceUntilIdle()
        assertEquals(2, api.fetchCount)
        assertEquals(listOf("profile-u3", "profile-u4", "profile-u5", "profile-u6", "profile-u7"), model.ids)
    }

    @Test
    fun contentSwipesDownToThreeCardsRefillToo() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(
            api,
            listOf(FeedItem.Ad(ad("a1")), FeedItem.News(news("n1")), FeedItem.Post(post("p1"))) + persons(1),
        )
        assertEquals(1, api.fetchCount)

        // Each content swipe takes the deck to three — no POST to wait for, the refill starts at once.
        api.pages += itemsPage(persons(2))
        model.swipeAd(ad("a1"), liked = false)
        advanceUntilIdle()
        assertEquals(2, api.fetchCount)
        assertEquals(listOf("news-n1", "post-p1", "profile-u1", "profile-u2"), model.ids)

        api.pages += itemsPage(persons(3))
        model.swipeNews(news("n1"), liked = true)
        advanceUntilIdle()
        assertEquals(3, api.fetchCount)
        assertEquals(listOf("post-p1", "profile-u1", "profile-u2", "profile-u3"), model.ids)

        api.pages += itemsPage(persons(4))
        model.swipePost(post("p1"), liked = false)
        advanceUntilIdle()
        assertEquals(4, api.fetchCount)
        assertEquals(listOf("profile-u1", "profile-u2", "profile-u3", "profile-u4"), model.ids)
    }

    @Test
    fun aContentSwipeThatLeavesMoreThanThreeCardsDoesNotRefill() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, listOf(FeedItem.News(news("n1"))) + persons(1, 2, 3, 4))
        model.swipeNews(news("n1"), liked = false)
        advanceUntilIdle()
        assertEquals(1, api.fetchCount)
    }

    @Test
    fun undoPutsTheCardBackOnTopAndLetsItBeServedAgain() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, persons(1, 2, 3, 4, 5, 6))
        model.swipe(person(1), SwipeAction.pass)
        advanceUntilIdle()

        model.undoLastSwipe()
        // The affordance goes away immediately, before the server answers.
        assertFalse(model.state.value.canUndo)
        assertEquals("profile-u2", model.ids.first())
        advanceUntilIdle()
        assertEquals(listOf("swipe u1 pass", "undo u1"), api.calls)
        assertEquals(listOf("profile-u1", "profile-u2"), model.ids.take(2))

        // No longer "swiped this session": once it leaves the deck some other way, a later
        // page may serve it again.
        model.reportAndRemove(person(1), "Spam")
        api.pages += itemsPage(persons(1))
        model.refresh()
        advanceUntilIdle()
        assertEquals("profile-u1", model.ids.last())
    }

    @Test
    fun undoFailureKeepsTheCardOutAndAlerts() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.undoError = ApiError.Http(status = 409, message = "Already matched")
        val model = loaded(api, persons(1, 2, 3, 4, 5, 6))
        model.swipe(person(1), SwipeAction.pass)
        advanceUntilIdle()
        model.undoLastSwipe()
        advanceUntilIdle()

        assertEquals("Already matched", model.state.value.errorMessage)
        assertEquals("profile-u2", model.ids.first())
        assertFalse(model.state.value.canUndo)
        // Nothing to undo now.
        model.undoLastSwipe()
        advanceUntilIdle()
        assertEquals(1, api.calls.count { it.startsWith("undo") })
    }

    // endregion

    // region Content cards

    @Test
    fun adRightSwipeOpensTheLinkAndCountsAClick() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, listOf(FeedItem.Ad(ad("a1"))) + persons(1, 2, 3, 4))
        events.clear()

        model.swipe(DeckItem.Ad(ad("a1")), liked = true)
        assertEquals("https://example.com/a1", model.state.value.urlToOpen)
        assertEquals(listOf("click ads/a1"), events)
        assertEquals("profile-u1", model.ids.first())
        // No swipe is ever recorded for content.
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())

        model.consumeUrlToOpen()
        assertNull(model.state.value.urlToOpen)
    }

    @Test
    fun adLeftSwipeOrLinklessAdJustLeavesTheDeck() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, listOf(FeedItem.Ad(ad("a1")), FeedItem.Ad(ad("a2", link = "  "))) + persons(1, 2, 3, 4))
        events.clear()

        model.swipeAd(ad("a1"), liked = false)
        model.swipeAd(ad("a2", link = "  "), liked = true)
        assertNull(model.state.value.urlToOpen)
        assertTrue(events.none { it.startsWith("click") })
        assertEquals("profile-u1", model.ids.first())
    }

    @Test
    fun newsRightSwipeSavesItAndItIsNeverReServed() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, listOf(FeedItem.News(news("n1"))) + persons(1, 2, 3, 4))

        model.swipeNews(news("n1"), liked = true)
        advanceUntilIdle()
        assertEquals(listOf("saveNews n1"), api.calls)
        assertNull(model.state.value.urlToOpen) // saving never opens the source

        api.pages += itemsPage(listOf(FeedItem.News(news("n1")), FeedItem.Person(person(5))))
        model.refresh()
        advanceUntilIdle()
        assertFalse("news-n1" in model.ids)
    }

    @Test
    fun newsLeftSwipeDoesNotSaveAndMayCycleBack() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, listOf(FeedItem.News(news("n1"))) + persons(1, 2, 3, 4))
        model.swipeNews(news("n1"), liked = false)
        api.pages += itemsPage(listOf(FeedItem.News(news("n1"))))
        model.refresh()
        advanceUntilIdle()
        assertTrue(api.calls.none { it.startsWith("saveNews") })
        assertEquals("news-n1", model.ids.last())
    }

    @Test
    fun openingTheNewsSourceKeepsTheCardAndCountsAClick() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, listOf(FeedItem.News(news("n1"))) + persons(1))
        events.clear()

        model.openNews(news("n1"))
        assertEquals("https://news.example/n1", model.state.value.urlToOpen)
        assertEquals(listOf("click news/n1"), events)
        assertEquals("news-n1", model.ids.first())

        model.reportNewsClick(news("n1"))
        assertEquals(listOf("click news/n1", "click news/n1"), events)
    }

    @Test
    fun postRightSwipeSavesAndAnEventAlsoRsvps() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val event = post("e1", kind = "event")
        val model = loaded(api, listOf(FeedItem.Post(post("p1")), FeedItem.Post(event)) + persons(1, 2, 3, 4))

        model.swipePost(post("p1"), liked = true)
        model.swipePost(event, liked = true)
        advanceUntilIdle()
        assertEquals(listOf("savePost p1", "savePost e1", "rsvp e1 true"), api.calls)

        api.pages += itemsPage(listOf(FeedItem.Post(post("p1")), FeedItem.Post(event)))
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.ids.none { it.startsWith("post-") })
    }

    @Test
    fun linkPostCtaOpensTheLinkAndCountsAClick() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val link = post("p1", kind = "link", link = "https://tat.example/gala")
        val model = loaded(api, listOf(FeedItem.Post(link)))
        events.clear()

        model.openPostLink(link)
        assertEquals("https://tat.example/gala", model.state.value.urlToOpen)
        assertEquals(listOf("click posts/p1"), events)
        assertEquals("post-p1", model.ids.first())

        model.consumeUrlToOpen()
        model.openPostLink(post("p2"))
        assertNull(model.state.value.urlToOpen)
    }

    @Test
    fun voteUpdatesTheDeckEntryAndTheOpenSheet() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val poll = post("p1", kind = "poll").copy(
            poll = Poll(options = listOf(PollOption("o1", "Yes"), PollOption("o2", "No")), counts = emptyMap()),
        )
        val voted = Poll(options = poll.poll!!.options, counts = mapOf("o1" to 1))
        api.voteResult = VoteResult(poll = voted, myVote = "o1")
        val model = loaded(api, listOf(FeedItem.Post(poll)) + persons(1))

        model.openPost(poll)
        model.vote(poll, "o1")
        advanceUntilIdle()

        assertEquals(listOf("vote p1 o1"), api.calls)
        val deckPost = (model.state.value.deck.first() as DeckItem.Post).post
        assertEquals("o1", deckPost.myVote)
        assertEquals(voted, deckPost.poll)
        assertEquals(deckPost, model.state.value.expandedPost)
    }

    @Test
    fun voteFailureAlertsAndLeavesThePostAlone() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.voteError = ApiError.Http(status = 400, message = "Poll closed")
        val poll = post("p1", kind = "poll")
        val model = loaded(api, listOf(FeedItem.Post(poll)))
        model.openPost(poll)
        model.vote(poll, "o1")
        advanceUntilIdle()

        assertEquals("Poll closed", model.state.value.errorMessage)
        assertEquals(poll, model.state.value.expandedPost)
        assertEquals(poll, (model.state.value.deck.first() as DeckItem.Post).post)
    }

    @Test
    fun rsvpOnlyRefreshesASheetThatIsStillOpen() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.rsvpResult = RsvpResult(attendeeCount = 13, going = true)
        val event = post("e1", kind = "event").copy(attendeeCount = 12, myRsvp = false)
        val model = loaded(api, listOf(FeedItem.Post(event)))

        model.openPost(event)
        model.rsvp(event, going = true)
        model.closePost() // dismissed before the server answered
        advanceUntilIdle()

        assertNull(model.state.value.expandedPost)
        val deckPost = (model.state.value.deck.first() as DeckItem.Post).post
        assertEquals(13, deckPost.attendeeCount)
        assertEquals(true, deckPost.myRsvp)
    }

    @Test
    fun impressionsAreReportedOncePerContentCardWhenItReachesTheTop() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(
            api,
            listOf(FeedItem.News(news("n1")), FeedItem.Person(person(1)), FeedItem.Ad(ad("a1")), FeedItem.Post(post("p1"))) +
                persons(2, 3, 4, 5),
        )
        assertEquals(listOf("impression news/n1"), events)

        model.swipeNews(news("n1"), liked = false) // top: u1 — profiles report nothing
        assertEquals(listOf("impression news/n1"), events)

        model.swipe(person(1), SwipeAction.pass) // top: a1
        assertEquals(listOf("impression news/n1", "impression ads/a1"), events)

        advanceUntilIdle()
        model.undoLastSwipe() // top: u1 again
        advanceUntilIdle()
        model.swipe(person(1), SwipeAction.pass) // top: a1 again — already counted
        advanceUntilIdle()
        assertEquals(listOf("impression news/n1", "impression ads/a1"), events)

        model.swipeAd(ad("a1"), liked = false) // top: p1
        assertEquals(listOf("impression news/n1", "impression ads/a1", "impression posts/p1"), events)
        // Opening its sheet doesn't count again.
        model.openPost(post("p1"))
        model.closePost()
        model.swipePost(post("p1"), liked = false) // top: u2
        advanceUntilIdle()
        assertEquals(listOf("impression news/n1", "impression ads/a1", "impression posts/p1"), events)
    }

    // endregion

    // region Routing, safety

    @Test
    fun swipeTopRoutesToWhateverKindIsOnTop() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(
            api,
            listOf(FeedItem.Ad(ad("a1")), FeedItem.News(news("n1")), FeedItem.Post(post("p1"))) + persons(1, 2, 3, 4),
        )
        model.swipeTop(liked = true) // ad → opens
        assertEquals("https://example.com/a1", model.state.value.urlToOpen)
        model.swipeTop(liked = true) // news → saves
        model.swipeTop(liked = false) // post → nothing recorded
        model.swipeTop(liked = true) // u1 → like
        advanceUntilIdle()
        assertEquals(listOf("saveNews n1", "swipe u1 like"), api.calls)
        assertEquals("profile-u2", model.ids.first())
    }

    @Test
    fun reportAndBlockRemoveTheCardImmediately() = runTest(dispatcher) {
        val api = FakeFeedApi()
        val model = loaded(api, persons(1, 2, 3, 4, 5))

        model.reportAndRemove(person(1), "Spam")
        model.blockAndRemove(person(2))
        assertEquals("profile-u3", model.ids.first())
        advanceUntilIdle()
        assertEquals(listOf("report u1 Spam", "block u2 Person 2"), api.calls)
        assertNull(model.state.value.errorMessage)
        // Report/block are not swipes: nothing to undo.
        assertFalse(model.state.value.canUndo)
    }

    @Test
    fun reportFailureAlertsButTheCardStaysGone() = runTest(dispatcher) {
        val api = FakeFeedApi()
        api.blockError = ApiError.Http(status = 500, message = "")
        val model = loaded(api, persons(1, 2, 3, 4, 5))
        model.blockAndRemove(person(1))
        advanceUntilIdle()
        assertEquals("Server error (500).", model.state.value.errorMessage)
        assertFalse("profile-u1" in model.ids)
    }

    // endregion
}
