package app.drokpo.android.features.likes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The saved-row swipe (iOS reveal-then-tap) and the in-app-browser double-tap guard. */
class RowSwipeTest {
    // A 400px row with an 88px "Remove" button; flicks are 300px/s and up.
    private val width = 400f
    private val reveal = 88f
    private val flick = 300f

    private fun target(offset: Float, velocity: Float = 0f) = swipeTarget(offset, velocity, width, reveal, flick)

    @Test
    fun aShortDragSnapsBackClosed() {
        assertEquals(SwipeTarget.Closed, target(-20f))
        assertEquals(SwipeTarget.Closed, target(-43f))
    }

    @Test
    fun pastHalfTheButtonRevealsIt() {
        assertEquals(SwipeTarget.Revealed, target(-44f))
        assertEquals(SwipeTarget.Revealed, target(-120f))
        assertEquals(SwipeTarget.Revealed, target(-239f))
    }

    @Test
    fun aLeftwardFlickOnlyRevealsNeverRemoves() {
        assertEquals(SwipeTarget.Revealed, target(-10f, velocity = -5_000f))
        // Far past the button but short of a full swipe: still just the button.
        assertEquals(SwipeTarget.Revealed, target(-200f, velocity = -20_000f))
    }

    @Test
    fun onlyADeliberateFullSwipeRemoves() {
        assertEquals(SwipeTarget.Removed, target(-width * FULL_SWIPE_FRACTION))
        assertEquals(SwipeTarget.Removed, target(-390f, velocity = -2_000f))
        // Easing back a little while still past the line still counts (iOS stays armed).
        assertEquals(SwipeTarget.Removed, target(-300f, velocity = 100f))
    }

    @Test
    fun aRightwardFlickCloses() {
        assertEquals(SwipeTarget.Closed, target(-88f, velocity = 400f))
        assertEquals(SwipeTarget.Closed, target(-350f, velocity = 400f))
    }

    @Test
    fun anUnmeasuredRowNeverRemoves() {
        assertEquals(SwipeTarget.Closed, swipeTarget(0f, 0f, width = 0f, revealWidth = reveal, flickVelocity = flick))
    }

    @Test
    fun aTapWhileARowIsOpenOnlyClosesIt() {
        val rows = OpenRowTracker()
        var opened = 0
        rows.tapOr { opened++ }
        assertEquals(1, opened)

        rows.openId = "liked-news-n-losar"
        rows.tapOr { opened++ }
        assertEquals(1, opened)
        assertNull(rows.openId)

        rows.tapOr { opened++ }
        assertEquals(2, opened)
    }

    @Test
    fun theBrowserOpensOncePerBurstOfTaps() {
        var clock = 10_000L
        val guard = RepeatTapGuard(windowMillis = 1_000L, now = { clock })
        var opens = 0
        guard.run { opens++ }
        clock += 120
        guard.run { opens++ }
        clock += 500
        guard.run { opens++ }
        assertEquals(1, opens)

        clock += 1_000
        guard.run { opens++ }
        assertEquals(2, opens)
    }
}
