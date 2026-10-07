package app.drokpo.android.features.feed

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import app.drokpo.android.core.model.AdCard
import app.drokpo.android.core.model.FeedCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeckState on a virtual clock: a frame every 16ms of test time, with the
 * snapshot apply notifications Android's frame loop would send (so the
 * departure's snapshotFlow sees the card move).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckStateTest {
    private class TestFrameClock(private val scheduler: TestCoroutineScheduler) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(FRAME_MILLIS)
            val result = onFrame(scheduler.currentTime * 1_000_000L)
            Snapshot.sendApplyNotifications()
            return result
        }
    }

    private companion object {
        const val FRAME_MILLIS = 16L
        const val DENSITY = 2.625f
        const val WIDTH = 1_080f
        const val HEIGHT = 1_800f
    }

    private val profile = DeckItem.Profile(FeedCard(uid = "u1", displayName = "Pema"))
    private val other = DeckItem.Profile(FeedCard(uid = "u2", displayName = "Dolma"))
    private val ad = DeckItem.Ad(AdCard(adId = "a1"))

    private val commits = mutableListOf<Pair<String, Boolean>>()

    private fun TestScope.deckState(scope: CoroutineScope = this): DeckState =
        DeckState(CoroutineScope(scope.coroutineContext + TestFrameClock(testScheduler))).apply {
            onCommit = { item, liked -> commits += item.id to liked }
            setGeometry(WIDTH, HEIGHT, DENSITY)
        }

    @Test
    fun aCommitHandsTheCardOverOnceAndIgnoresRepeats() = runTest {
        val deck = deckState()
        deck.top = profile

        deck.commit(profile, liked = true)
        // A double tap on like, or the like button while a drag commit is in flight.
        deck.commit(profile, liked = true)
        deck.swipe(profile, liked = false)

        assertEquals(listOf("profile-u1" to true), commits)
        assertEquals(listOf(profile), deck.departing.toList())
        assertTrue(deck.isDeparting(profile.id))
    }

    @Test
    fun theDepartureEndsOnceTheCardHasLeftTheDeck() = runTest {
        val deck = deckState()
        deck.top = profile
        deck.commit(profile, liked = false)
        runCurrent()
        assertTrue(deck.isDeparting(profile.id))

        // The button fly-off takes 280ms; well inside the 700ms cap.
        advanceTimeBy(450)
        runCurrent()
        assertTrue(deck.departing.isEmpty())
        // Its motion is forgotten: the id starts at rest if it ever comes back.
        assertEquals(Offset.Zero, deck.motion(profile.id).value)
    }

    @Test
    fun aDragCommitFliesOffWithTheReleaseSpeed() = runTest {
        val deck = deckState()
        deck.top = profile
        deck.dragTo(profile.id, Offset(400f, 30f))
        runCurrent()
        deck.commit(profile, liked = true, velocity = Velocity(3_000f, 200f))
        advanceTimeBy(FRAME_MILLIS * 4)
        runCurrent()
        val midFlight = deck.motion(profile.id).value
        assertTrue(midFlight.x > 400f)

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(deck.departing.isEmpty())
    }

    @Test
    fun cancellingTheScopeEndsEveryDeparture() = runTest {
        val parent = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val deck = deckState(parent)
        deck.top = profile
        deck.commit(profile, liked = true)
        deck.commit(ad, liked = false)
        runCurrent()
        assertEquals(2, deck.departing.size)

        parent.cancel()
        runCurrent()
        assertTrue(deck.departing.isEmpty())
    }

    @Test
    fun aProgrammaticSwipeOfACardThatIsNotOnTopJustRecordsIt() = runTest {
        val deck = deckState()
        deck.top = other
        // The expanded profile sheet's like, for a card that's no longer the top one.
        deck.swipe(profile, liked = true)
        assertEquals(listOf("profile-u1" to true), commits)
        assertTrue(deck.departing.isEmpty())

        deck.swipe(other, liked = false)
        assertEquals(listOf("profile-u1" to true, "profile-u2" to false), commits)
        assertEquals(listOf(other), deck.departing.toList())
        advanceTimeBy(1_000)
    }

    @Test
    fun undoBringsTheLastProfileBackFromTheSideItLeft() = runTest {
        val deck = deckState()
        deck.top = profile
        deck.commit(profile, liked = false)
        // A later content swipe doesn't replace the profile undo would restore.
        deck.top = ad
        deck.commit(ad, liked = true)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(deck.departing.isEmpty())

        deck.expectReturnOfLastProfile()
        val returning = deck.motion(profile.id)
        assertEquals(-deck.flyDistancePx, returning.value.x)
        assertEquals(0f, returning.value.y)
        assertTrue(isOffDeck(returning.value, DENSITY, WIDTH, HEIGHT))
        assertTrue(deck.consumePendingReturn(profile.id))
        assertFalse(deck.consumePendingReturn(profile.id))
        // Any other card still starts at rest.
        assertEquals(Offset.Zero, deck.motion(other.id).value)
        assertFalse(deck.consumePendingReturn(other.id))
    }

    @Test
    fun aLikedProfileComesBackFromTheRight() = runTest {
        val deck = deckState()
        deck.top = profile
        deck.commit(profile, liked = true)
        advanceTimeBy(1_000)
        runCurrent()

        deck.expectReturnOfLastProfile()
        assertEquals(deck.flyDistancePx, deck.motion(profile.id).value.x)
    }

    @Test
    fun aReleasedDragSpringsBackWithoutOvershooting() = runTest {
        val deck = deckState()
        deck.top = profile
        deck.dragTo(profile.id, Offset(250f, 40f))
        runCurrent()
        assertEquals(Offset(250f, 40f), deck.motion(profile.id).value)

        deck.springBack(profile.id)
        var minX = Float.MAX_VALUE
        repeat(40) {
            advanceTimeBy(FRAME_MILLIS)
            runCurrent()
            minX = minOf(minX, deck.motion(profile.id).value.x)
        }
        val settled = deck.motion(profile.id).value
        assertEquals(0f, settled.x, 0.5f)
        assertEquals(0f, settled.y, 0.5f)
        // Critically damped (iOS `.spring(duration: 0.3)`): never swings past the centre.
        assertTrue("overshot to $minX", minX > -0.5f)
        assertTrue(commits.isEmpty())
    }

    @Test
    fun aDepartingCardIgnoresDragsAndSpringBacks() = runTest {
        val deck = deckState()
        deck.top = profile
        deck.commit(profile, liked = true)
        advanceTimeBy(FRAME_MILLIS * 3)
        runCurrent()
        val inFlight = deck.motion(profile.id).value
        deck.dragTo(profile.id, Offset.Zero)
        deck.springBack(profile.id)
        runCurrent()
        assertTrue(deck.motion(profile.id).value.x >= inFlight.x)
        advanceTimeBy(1_000)
    }
}
