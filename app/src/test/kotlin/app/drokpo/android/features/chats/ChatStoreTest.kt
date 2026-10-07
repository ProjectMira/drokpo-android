package app.drokpo.android.features.chats

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.LastMessage
import app.drokpo.android.core.model.Match
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreTest {
    private val me = "me"
    private val pema = FeedCard(uid = "pema", displayName = "Pema")
    private val karma = FeedCard(uid = "karma", displayName = "Karma")

    private class FakeMatches : MatchesListener {
        var listened = mutableListOf<String>()
        var removed = 0
        private var onMatches: ((List<Match>) -> Unit)? = null
        private var onError: ((Exception) -> Unit)? = null

        override fun listen(uid: String, onMatches: (List<Match>) -> Unit, onError: (Exception) -> Unit): ListenerHandle {
            listened += uid
            this.onMatches = onMatches
            this.onError = onError
            return ListenerHandle { removed++ }
        }

        fun emit(vararg matches: Match) = onMatches!!(matches.toList())
        fun fail(error: Exception) = onError!!(error)
    }

    private val listener = FakeMatches()
    private var loadCalls = 0
    private var load: suspend () -> List<Match> = { emptyList() }
    private var unmatch: suspend (String) -> Unit = {}
    private val unmatched = mutableListOf<String>()

    private fun store() = ChatStore(
        matchesListener = listener,
        loadMatches = {
            loadCalls++
            load()
        },
        unmatchRequest = { id ->
            unmatched += id
            unmatch(id)
        },
    )

    private fun match(
        id: String,
        other: String,
        text: String? = null,
        sender: String? = null,
        unread: Int = 0,
        lastAt: Instant? = null,
        createdAt: Instant? = null,
    ) = Match(
        matchId = id,
        users = listOf(me, other),
        status = "active",
        lastMessage = if (text != null || lastAt != null) {
            LastMessage(text = text, senderId = sender, createdAt = lastAt?.toString())
        } else {
            null
        },
        unreadCount = mapOf(me to unread, other to 9),
        createdAt = createdAt?.toString(),
    )

    private fun joined(vararg cards: FeedCard) = cards.map { Match(otherUser = it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun idleStoreIsLoadingAndEmpty() {
        val store = store()
        assertTrue(store.isLoading.value)
        assertEquals(emptyList<ChatStore.Entry>(), store.entries.value)
        assertEquals(0, store.totalUnread.value)
        assertEquals(emptyList<String>(), listener.listened)
    }

    @Test
    fun buildsSortedEntriesAndJoinsProfiles() {
        load = { joined(pema, karma) }
        val store = store()
        store.start(me)
        listener.emit(
            match("m1", "pema", text = "hi", sender = "pema", unread = 2, lastAt = Instant.ofEpochSecond(300)),
            match("m2", "karma", createdAt = Instant.ofEpochSecond(500)),
            match("m3", "karma", text = "old", sender = me, lastAt = Instant.ofEpochSecond(100)),
        )

        val entries = store.entries.value
        assertEquals(listOf("m2", "m1", "m3"), entries.map { it.matchId })
        assertEquals(listOf("karma", "pema", "karma"), entries.map { it.otherUid })
        assertEquals(listOf(karma, pema, karma), entries.map { it.otherUser })
        assertEquals(2, entries[1].unread)
        assertEquals("hi", entries[1].lastMessageText)
        assertEquals("pema", entries[1].lastMessageSenderId)
        assertEquals(Instant.ofEpochSecond(500), entries[0].sortDate)
        assertFalse(store.isLoading.value)
        assertEquals(1, loadCalls)

        assertEquals(2, store.totalUnread.value)
        assertEquals(listOf("m2"), store.newMatches.value.map { it.matchId })
        assertEquals(listOf("m1", "m3"), store.conversations.value.map { it.matchId })
    }

    @Test
    fun staysLoadingUntilTheProfileJoinFinishes() {
        val gate = CompletableDeferred<List<Match>>()
        load = { gate.await() }
        val store = store()
        store.start(me)
        listener.emit(match("m1", "pema", text = "hi", sender = "pema"))

        assertTrue(store.isLoading.value)
        assertNull(store.entries.value.single().otherUser)

        gate.complete(joined(pema))
        assertFalse(store.isLoading.value)
        assertEquals(pema, store.entries.value.single().otherUser)
    }

    @Test
    fun knownProfilesSkipTheRestCall() {
        load = { joined(pema) }
        val store = store()
        store.start(me)
        listener.emit(match("m1", "pema"))
        listener.emit(match("m1", "pema", text = "new", sender = "pema", unread = 1))
        assertEquals(1, loadCalls)
        assertEquals(pema, store.entries.value.single().otherUser)
        assertEquals(1, store.totalUnread.value)
        assertFalse(store.isLoading.value)
    }

    @Test
    fun snapshotsDuringAnInFlightJoinTriggerOneMoreRound() {
        val first = CompletableDeferred<List<Match>>()
        val gates = ArrayDeque(listOf(first))
        load = { gates.removeFirstOrNull()?.await() ?: joined(pema, karma) }
        val store = store()
        store.start(me)
        listener.emit(match("m1", "pema"))
        // A new match arrives while the first GET /api/matches is still in flight.
        listener.emit(match("m1", "pema"), match("m2", "karma"))
        assertEquals(1, loadCalls)

        first.complete(joined(pema)) // served before karma's match existed
        assertEquals(2, loadCalls)
        assertEquals(setOf(pema, karma), store.entries.value.map { it.otherUser }.toSet())
        assertFalse(store.isLoading.value)
    }

    @Test
    fun aFailedJoinIsRetriedForASnapshotThatArrivedMeanwhile() {
        // iOS starts a GET /api/matches per snapshot with missing profiles, so a later one can
        // still fill the names after an earlier one failed (cache snapshot, then server snapshot).
        val first = CompletableDeferred<List<Match>>()
        val gates = ArrayDeque(listOf(first))
        load = { gates.removeFirstOrNull()?.await() ?: joined(pema, karma) }
        val store = store()
        store.start(me)
        listener.emit(match("m1", "pema"))
        listener.emit(match("m1", "pema"), match("m2", "karma"))

        first.completeExceptionally(ApiError.Http(500, "Internal error"))
        assertEquals(2, loadCalls)
        assertEquals(setOf(pema, karma), store.entries.value.map { it.otherUser }.toSet())
        assertEquals("Internal error", store.errorMessage.value)
        assertFalse(store.isLoading.value)
    }

    @Test
    fun matchesWithoutAnotherUserAreSkipped() {
        val built = ChatStore.buildEntries(
            matches = listOf(
                Match(matchId = "solo", users = listOf(me)),
                Match(matchId = "none", users = null),
                Match(matchId = "ok", users = listOf("pema", me)),
            ),
            uid = me,
            profiles = mapOf("pema" to pema),
        )
        assertEquals(listOf("ok"), built.entries.map { it.matchId })
        assertEquals("pema", built.entries.single().otherUid)
        assertFalse(built.missingProfiles)
        assertEquals(Instant.EPOCH, built.entries.single().sortDate)
        assertEquals(0, built.entries.single().unread)
    }

    @Test
    fun listenerErrorStopsLoadingWithTheMessage() {
        val store = store()
        store.start(me)
        listener.fail(IllegalStateException("PERMISSION_DENIED: Missing or insufficient permissions."))
        assertEquals("PERMISSION_DENIED: Missing or insufficient permissions.", store.errorMessage.value)
        assertFalse(store.isLoading.value)
    }

    @Test
    fun profileJoinFailureKeepsEntriesAndShowsTheError() {
        load = { throw ApiError.Http(500, "Internal error") }
        val store = store()
        store.start(me)
        listener.emit(match("m1", "pema", text = "hi", sender = "pema"))
        assertEquals("Internal error", store.errorMessage.value)
        assertFalse(store.isLoading.value)
        assertEquals(listOf("m1"), store.entries.value.map { it.matchId })
        // No snapshot arrived meanwhile: no retry until the next one.
        assertEquals(1, loadCalls)

        store.setError(null)
        assertNull(store.errorMessage.value)
    }

    @Test
    fun startIsIdempotentPerUidAndRestartsForAnother() {
        val store = store()
        store.start(me)
        store.start(me)
        assertEquals(listOf(me), listener.listened)
        assertEquals(0, listener.removed)

        store.start("someone-else")
        assertEquals(listOf(me, "someone-else"), listener.listened)
        assertEquals(1, listener.removed)
    }

    @Test
    fun stopRemovesTheListenerAndResets() {
        load = { joined(pema) }
        val store = store()
        store.start(me)
        listener.emit(match("m1", "pema", text = "hi", sender = "pema", unread = 3))
        store.setError("boom")

        store.stop()
        assertEquals(1, listener.removed)
        assertEquals(emptyList<ChatStore.Entry>(), store.entries.value)
        assertEquals(0, store.totalUnread.value)
        assertTrue(store.isLoading.value)
        assertNull(store.errorMessage.value)

        // A late callback from the removed listener is ignored.
        listener.emit(match("m1", "pema", text = "late", sender = "pema"))
        assertEquals(emptyList<ChatStore.Entry>(), store.entries.value)

        // Starting again for the same uid attaches a fresh listener (and re-joins profiles).
        store.start(me)
        listener.emit(match("m1", "pema", text = "hi", sender = "pema"))
        assertEquals(2, loadCalls)
        assertEquals(pema, store.entries.value.single().otherUser)
    }

    @Test
    fun clearUnreadIsOptimisticAndLocal() {
        load = { joined(pema, karma) }
        val store = store()
        store.start(me)
        listener.emit(
            match("m1", "pema", text = "a", sender = "pema", unread = 2, lastAt = Instant.ofEpochSecond(20)),
            match("m2", "karma", text = "b", sender = "karma", unread = 1, lastAt = Instant.ofEpochSecond(10)),
        )
        assertEquals(3, store.totalUnread.value)
        store.clearUnread("m1")
        assertEquals(listOf(0, 1), store.entries.value.map { it.unread })
        assertEquals(1, store.totalUnread.value)
        assertEquals(listOf(0, 1), store.conversations.value.map { it.unread })
    }

    @Test
    fun unmatchHidesTheRowUntilTheListenerDropsIt() {
        load = { joined(pema, karma) }
        val gate = CompletableDeferred<Unit>()
        unmatch = { gate.await() }
        val store = store()
        store.start(me)
        listener.emit(match("m1", "pema", text = "a", sender = "pema"), match("m2", "karma", text = "b", sender = me))

        val result = store.unmatch("m1")
        assertEquals(setOf("m1"), store.unmatching.value)
        assertFalse(result.isCompleted)
        // A second call while pending doesn't POST twice; it shares the outcome.
        assertSame(result, store.unmatch("m1"))
        assertEquals(listOf("m1"), unmatched)

        gate.complete(Unit)
        assertTrue(result.getCompleted())
        assertEquals(setOf("m1"), store.unmatching.value)
        assertNull(store.errorMessage.value)
        // Went through: the row stays hidden, and nothing is posted again.
        assertTrue(store.unmatch("m1").getCompleted())
        assertEquals(listOf("m1"), unmatched)
        // The listener drops the match once its status flips to "unmatched".
        listener.emit(match("m2", "karma", text = "b", sender = me))
        assertEquals(emptySet<String>(), store.unmatching.value)
        assertEquals(listOf("m2"), store.entries.value.map { it.matchId })
    }

    @Test
    fun failedUnmatchBringsTheRowBack() {
        load = { joined(pema) }
        unmatch = { throw ApiError.Http(404, "Match not found") }
        val store = store()
        store.start(me)
        listener.emit(match("m1", "pema", text = "a", sender = "pema"))

        // Fails before the call even returns (as offline, within milliseconds): the swipe row
        // learns it from the result, not from watching `unmatching`.
        val result = store.unmatch("m1")
        assertFalse(result.getCompleted())
        assertEquals(emptySet<String>(), store.unmatching.value)
        assertEquals("Match not found", store.errorMessage.value)
        assertEquals(listOf("m1"), store.entries.value.map { it.matchId })

        // The row is usable again: a retry posts again.
        store.setError(null)
        assertFalse(store.unmatch("m1").getCompleted())
        assertEquals(listOf("m1", "m1"), unmatched)
    }
}
