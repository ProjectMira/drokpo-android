package app.drokpo.android.features.communities

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.AdCard
import app.drokpo.android.core.model.CommunitiesHomeResponse
import app.drokpo.android.core.model.CommunityListResponse
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.FeedItem
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.Poll
import app.drokpo.android.core.model.PollOption
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.VoteResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunitiesModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val tat = CommunityProfile(uid = "c-tat", name = "TAT", joined = true, memberCount = 10)
    private val zurich = CommunityProfile(uid = "c-zurich", name = "Zürich", joined = false, memberCount = 3)
    private val poll = CommunityPostCard(
        postId = "p-poll",
        kind = "poll",
        poll = Poll(listOf(PollOption("a", "A"), PollOption("b", "B")), mapOf("a" to 1, "b" to 0)),
    )
    private val event = CommunityPostCard(postId = "p-event", kind = "event", attendeeCount = 4, myRsvp = false)
    private val ad = AdCard(adId = "ad-1", title = "Ad")

    private fun homeWith(mine: List<CommunityProfile>, items: List<FeedItem>) =
        CommunitiesHomeResponse(communities = mine, items = items)

    // region Loading

    @Test
    fun loadsJoinedRailAndFeedWithoutAskingForSuggestions() = runTest {
        val api = FakeCommunitiesApi().apply {
            home = { homeWith(listOf(tat), listOf(FeedItem.Post(poll), FeedItem.Ad(ad))) }
        }
        val model = CommunitiesModel(api, reportClick = {})
        assertTrue(model.state.value.isLoading)
        advanceUntilIdle()

        val state = model.state.value
        assertEquals(listOf(tat), state.mine)
        assertEquals(2, state.items.size)
        assertFalse(state.isLoading)
        assertTrue(state.hasLoaded)
        assertEquals(listOf("home"), api.calls)
        assertTrue(state.showsRail)
        assertFalse(state.showsDiscover)
        assertTrue(state.showsFeedHeader)
    }

    @Test
    fun nothingJoinedFetchesTwentySuggestions() = runTest {
        val api = FakeCommunitiesApi().apply {
            directory = { CommunityListResponse(listOf(tat, zurich)) }
        }
        val model = CommunitiesModel(api, reportClick = {})
        advanceUntilIdle()

        assertEquals(listOf("home", "directory(20)"), api.calls)
        val state = model.state.value
        assertEquals(listOf(tat, zurich), state.discover)
        assertTrue(state.showsDiscover)
        assertFalse(state.showsRail)
        assertFalse(state.showsFeedHeader)
        // Nothing joined → no "Posts from your communities…" text either.
        assertFalse(state.showsFeedEmpty)
    }

    @Test
    fun onAppearReloadsOnlyAfterTheFirstSuccessfulLoad() = runTest {
        val api = FakeCommunitiesApi().apply { home = { homeWith(listOf(tat), emptyList()) } }
        val model = CommunitiesModel(api, reportClick = {})
        // First composition: .task and .onAppear both fire; only .task loads.
        model.onAppear()
        advanceUntilIdle()
        assertEquals(listOf("home"), api.calls)

        // Popping back from a pushed page reloads.
        model.onAppear()
        advanceUntilIdle()
        assertEquals(listOf("home", "home"), api.calls)
    }

    @Test
    fun failedLoadAlertsAndDoesNotArmReloadOnAppear() = runTest {
        val api = FakeCommunitiesApi().apply { home = { throw ApiError.Http(500, "boom") } }
        val model = CommunitiesModel(api, reportClick = {})
        advanceUntilIdle()

        val state = model.state.value
        assertEquals("boom", state.errorMessage)
        assertFalse(state.hasLoaded)
        assertFalse(state.isLoading)
        // iOS: nothing joined and not loading → the discover section (with its empty text).
        assertTrue(state.showsDiscover)

        model.onAppear()
        advanceUntilIdle()
        assertEquals(listOf("home"), api.calls)

        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun failedSuggestionsStillKeepTheHomeResponse() = runTest {
        val api = FakeCommunitiesApi().apply {
            home = { homeWith(emptyList(), listOf(FeedItem.Ad(ad))) }
            directory = { throw ApiError.Http(503, "unavailable") }
        }
        val model = CommunitiesModel(api, reportClick = {})
        advanceUntilIdle()

        assertEquals(listOf(FeedItem.Ad(ad)), model.state.value.items)
        assertEquals("unavailable", model.state.value.errorMessage)
    }

    @Test
    fun leavingTheLastCommunityNeverFlashesAnEmptyDiscoverSection() = runTest {
        val directoryGate = Gate<CommunityListResponse>()
        var joinedNow = listOf(tat)
        val api = FakeCommunitiesApi().apply {
            home = { homeWith(joinedNow, emptyList()) }
            directory = { directoryGate.await() }
        }
        val model = CommunitiesModel(api, reportClick = {})
        advanceUntilIdle()
        assertTrue(model.state.value.showsRail)

        // Every state the screen could render from here on.
        val seen = mutableListOf<CommunitiesUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { seen += it } }

        // Left TAT on its pushed page, then popped back: the reload finds nothing joined.
        joinedNow = emptyList()
        model.onAppear()
        advanceUntilIdle()
        assertEquals(listOf("home", "home", "directory(20)"), api.calls)
        // The suggestions are still on their way: the rail stays up meanwhile.
        assertEquals(listOf(tat), model.state.value.mine)
        assertTrue(model.state.value.showsRail)

        directoryGate.open(CommunityListResponse(listOf(zurich)))
        advanceUntilIdle()
        val state = model.state.value
        assertTrue(state.mine.isEmpty())
        assertEquals(listOf(zurich), state.discover)
        assertTrue(state.showsDiscover)
        assertFalse(state.isLoading)
        // "No communities to discover yet." was never on screen.
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.none { it.showsDiscover && it.discover.isEmpty() })
    }

    @Test
    fun aReloadWithFailingSuggestionsStillAppliesTheHomeResponse() = runTest {
        var joinedNow = listOf(tat)
        val api = FakeCommunitiesApi().apply {
            home = {
                if (joinedNow.isEmpty()) {
                    homeWith(emptyList(), listOf(FeedItem.Ad(ad)))
                } else {
                    homeWith(joinedNow, listOf(FeedItem.Post(poll)))
                }
            }
            directory = { throw ApiError.Http(503, "unavailable") }
        }
        val model = CommunitiesModel(api, reportClick = {})
        advanceUntilIdle()

        joinedNow = emptyList()
        model.onAppear()
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state.mine.isEmpty())
        assertEquals(listOf(FeedItem.Ad(ad)), state.items)
        assertEquals("unavailable", state.errorMessage)
        assertFalse(state.isLoading)
    }

    @Test
    fun refreshShowsTheIndicatorUntilTheLoadFinishes() = runTest {
        val gate = Gate<CommunitiesHomeResponse>()
        var first = true
        val api = FakeCommunitiesApi().apply {
            home = {
                if (first) {
                    first = false
                    homeWith(listOf(tat), emptyList())
                } else {
                    gate.await()
                }
            }
        }
        val model = CommunitiesModel(api, reportClick = {})
        advanceUntilIdle()

        model.refresh()
        advanceUntilIdle()
        assertTrue(model.state.value.isRefreshing)
        assertTrue(model.state.value.isLoading)
        // Already loaded once: no centre spinner and the rail/feed stay up (no blink).
        assertFalse(model.state.value.showsSpinner)
        assertTrue(model.state.value.showsFeedEmpty)

        gate.open(homeWith(listOf(tat, zurich), emptyList()))
        advanceUntilIdle()
        assertFalse(model.state.value.isRefreshing)
        assertFalse(model.state.value.isLoading)
        assertEquals(listOf(tat, zurich), model.state.value.mine)
    }

    @Test
    fun aNewerLoadSupersedesAnOlderOne() = runTest {
        val slow = Gate<CommunitiesHomeResponse>()
        var call = 0
        val api = FakeCommunitiesApi().apply {
            home = {
                call++
                when (call) {
                    1 -> homeWith(listOf(tat), emptyList())
                    2 -> slow.await()
                    else -> homeWith(listOf(zurich), emptyList())
                }
            }
        }
        val model = CommunitiesModel(api, reportClick = {})
        advanceUntilIdle()
        model.onAppear() // slow reload
        advanceUntilIdle()
        model.refresh() // supersedes it
        advanceUntilIdle()
        slow.open(homeWith(listOf(tat), listOf(FeedItem.Post(poll))))
        advanceUntilIdle()

        assertEquals(listOf(zurich), model.state.value.mine)
        assertTrue(model.state.value.items.isEmpty())
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun firstLoadShowsTheSpinnerOnlyWithNothingToShow() {
        assertTrue(CommunitiesUiState().showsSpinner)
        assertFalse(CommunitiesUiState().showsDiscover)
        assertFalse(CommunitiesUiState(mine = listOf(tat)).showsSpinner)
        assertFalse(CommunitiesUiState(items = listOf(FeedItem.Ad(ad))).showsSpinner)
        assertFalse(CommunitiesUiState(isLoading = false).showsSpinner)
    }

    @Test
    fun feedSkipsPersonAndNewsItems() {
        val state = CommunitiesUiState(
            items = listOf(
                FeedItem.Person(FeedCard(uid = "u1")),
                FeedItem.Post(poll),
                FeedItem.News(NewsCard(newsId = "n1")),
                FeedItem.Ad(ad),
                // A repeated item renders once (lazy-list keys must be unique).
                FeedItem.Post(poll),
            ),
        )
        assertEquals(listOf("post-p-poll", "ad-ad-1"), state.feed.map { it.id })
    }

    // endregion

    // region Vote / RSVP / links

    @Test
    fun voteReplacesThePollAndMyVoteInPlace() = runTest {
        val updatedPoll = Poll(poll.poll!!.options, mapOf("a" to 1, "b" to 1))
        val api = FakeCommunitiesApi().apply {
            home = { homeWith(listOf(tat), listOf(FeedItem.Ad(ad), FeedItem.Post(poll), FeedItem.Post(event))) }
            vote = { _, _ -> VoteResult(poll = updatedPoll, myVote = "b") }
        }
        val model = CommunitiesModel(api, reportClick = {})
        advanceUntilIdle()

        model.vote(poll, "b")
        advanceUntilIdle()

        assertTrue("vote(p-poll,b)" in api.calls)
        val items = model.state.value.items
        assertEquals(3, items.size)
        val voted = (items[1] as FeedItem.Post).post
        assertEquals(updatedPoll, voted.poll)
        assertEquals("b", voted.myVote)
        assertSame((items[2] as FeedItem.Post).post, event)
    }

    @Test
    fun rsvpUpdatesAttendeesAndMyRsvp() = runTest {
        val api = FakeCommunitiesApi().apply {
            home = { homeWith(listOf(tat), listOf(FeedItem.Post(event))) }
            rsvp = { _, going -> RsvpResult(attendeeCount = if (going) 5 else 4, going = going) }
        }
        val model = CommunitiesModel(api, reportClick = {})
        advanceUntilIdle()

        model.rsvp(event, going = true)
        advanceUntilIdle()
        var post = (model.state.value.items.single() as FeedItem.Post).post
        assertEquals(5, post.attendeeCount)
        assertEquals(true, post.myRsvp)

        model.rsvp(post, going = false)
        advanceUntilIdle()
        post = (model.state.value.items.single() as FeedItem.Post).post
        assertEquals(4, post.attendeeCount)
        assertEquals(false, post.myRsvp)
        assertEquals(listOf("home", "rsvp(p-event,true)", "rsvp(p-event,false)"), api.calls)
    }

    @Test
    fun voteFailureAlertsAndLeavesThePost() = runTest {
        val api = FakeCommunitiesApi().apply {
            home = { homeWith(listOf(tat), listOf(FeedItem.Post(poll))) }
            vote = { _, _ -> throw ApiError.Http(400, "Poll closed") }
        }
        val model = CommunitiesModel(api, reportClick = {})
        advanceUntilIdle()
        model.vote(poll, "a")
        advanceUntilIdle()

        assertEquals("Poll closed", model.state.value.errorMessage)
        assertEquals(poll, (model.state.value.items.single() as FeedItem.Post).post)
    }

    @Test
    fun aVoteOrRsvpInFlightFinishesAfterTheCoverCloses() = runTest {
        val voteGate = Gate<VoteResult>()
        val rsvpGate = Gate<RsvpResult>()
        val finished = mutableListOf<String>()
        val api = FakeCommunitiesApi().apply {
            home = { homeWith(listOf(tat), listOf(FeedItem.Post(poll), FeedItem.Post(event))) }
            vote = { _, _ -> voteGate.await().also { finished += "vote" } }
            rsvp = { _, _ -> rsvpGate.await().also { finished += "rsvp" } }
        }
        val store = ViewModelStore()
        val model = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { CommunitiesModel(api, reportClick = {}) } },
        )[CommunitiesModel::class]
        advanceUntilIdle()

        model.vote(poll, "b")
        model.rsvp(event, going = true)
        advanceUntilIdle()
        store.clear() // the cover was closed
        voteGate.open(VoteResult(poll = poll.poll, myVote = "b"))
        rsvpGate.open(RsvpResult(attendeeCount = 5, going = true))
        advanceUntilIdle()

        assertEquals(listOf("vote", "rsvp"), finished)
        val items = model.state.value.items
        assertEquals("b", (items[0] as FeedItem.Post).post.myVote)
        assertEquals(true, (items[1] as FeedItem.Post).post.myRsvp)
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun updatingPostIgnoresPostsNoLongerListed() {
        val items = listOf<FeedItem>(FeedItem.Ad(ad))
        assertSame(items, items.updatingPost("gone") { it.copy(myVote = "x") })
    }

    @Test
    fun openedLinksReportAClick() = runTest {
        val clicks = mutableListOf<String>()
        val model = CommunitiesModel(FakeCommunitiesApi(), reportClick = { clicks += it })
        model.linkOpened("posts/p1")
        model.linkOpened("ads/ad-1")
        assertEquals(listOf("posts/p1", "ads/ad-1"), clicks)
    }

    // endregion

    @Test
    fun memberCountLabelPluralises() {
        assertEquals("0 members", memberCountLabel(null))
        assertEquals("0 members", memberCountLabel(0))
        assertEquals("1 member", memberCountLabel(1))
        assertEquals("128 members", memberCountLabel(128))
    }
}
