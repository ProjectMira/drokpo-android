package app.drokpo.android.features.profile

import app.drokpo.android.core.model.Socials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLogicTest {
    private val a = photo("a")
    private val b = photo("b")
    private val c = photo("c")
    private val d = photo("d")

    @Test
    fun movePhotoForwardLandsOnTargetSlot() {
        // iOS move(fromOffsets: 0, toOffset: to + 1) — the dragged photo takes the target's index.
        assertEquals(listOf(b, c, a, d), movePhoto(listOf(a, b, c, d), a.id, c.id))
    }

    @Test
    fun movePhotoBackwardLandsOnTargetSlot() {
        assertEquals(listOf(a, d, b, c), movePhoto(listOf(a, b, c, d), d.id, b.id))
    }

    @Test
    fun movePhotoOntoNeighbourSwaps() {
        assertEquals(listOf(b, a, c), movePhoto(listOf(a, b, c), a.id, b.id))
        assertEquals(listOf(a, c, b), movePhoto(listOf(a, b, c), c.id, b.id))
    }

    @Test
    fun movePhotoIgnoresSelfAndUnknownIds() {
        val photos = listOf(a, b, c)
        assertSame(photos, movePhoto(photos, a.id, a.id))
        assertSame(photos, movePhoto(photos, "missing", b.id))
        assertSame(photos, movePhoto(photos, a.id, "missing"))
    }

    @Test
    fun answeredPromptsFollowVocabularyOrderAndSkipEmpty() {
        val answered = answeredPrompts(
            mapOf(
                "perfectWeekend" to "Hiking",
                "lookingFor" to "New friends",
                "teaChoice" to "",
                "unknownKey" to "ignored",
            ),
        )
        assertEquals(listOf("I'm here for" to "New friends", "My perfect weekend" to "Hiking"), answered)
        assertEquals(emptyList<Pair<String, String>>(), answeredPrompts(null))
    }

    @Test
    fun socialRowsShowInstagramAlwaysAndTheRestOnlyWhenSet() {
        assertEquals(listOf("Instagram" to null), socialRows(null))
        assertEquals(listOf("Instagram" to null), socialRows(Socials(instagram = "", youtube = "", tiktok = "")))
        assertEquals(
            listOf("Instagram" to "@tenzin", "YouTube" to "TenzinTV", "TikTok" to "@tenzin.t"),
            socialRows(Socials(instagram = "tenzin", youtube = "TenzinTV", tiktok = "tenzin.t")),
        )
    }

    @Test
    fun discoveryFooterCopy() {
        assertEquals("Your profile can appear in other people's swipe deck.", discoveryFooter(true))
        assertEquals(
            "Your profile is hidden — nobody can find you by swiping. Your matches and chats keep working.",
            discoveryFooter(false),
        )
    }

    @Test
    fun listsJoinWithCommas() {
        assertEquals("Tibetan, English", listOf("Tibetan", "English").joinedOrNull())
        assertEquals(null, (null as List<String>?).joinedOrNull())
    }

    // region Photo strip gestures

    private val addKey = "add"

    /** Three 90px photos 8px apart, then the "+" tile. */
    private val strip = listOf(
        StripTile(key = "p0", index = 0, start = 0, size = 90),
        StripTile(key = "p1", index = 1, start = 98, size = 90),
        StripTile(key = "p2", index = 2, start = 196, size = 90),
        StripTile(key = addKey, index = 3, start = 294, size = 90),
    )

    @Test
    fun photoTileAtFindsPhotosButNotGapsOrThePlusTile() {
        assertEquals("p0", photoTileAt(0f, strip, addKey)?.key)
        assertEquals("p1", photoTileAt(140f, strip, addKey)?.key)
        assertEquals("p2", photoTileAt(286f, strip, addKey)?.key)
        assertNull(photoTileAt(94f, strip, addKey)) // the 8px gap
        assertNull(photoTileAt(330f, strip, addKey)) // "+" tile
        assertNull(photoTileAt(500f, strip, addKey))
        assertNull(photoTileAt(140f, strip, addKey, except = "p1")) // the dragged tile itself
    }

    @Test
    fun dragTranslationKeepsTheTileUnderTheFinger() {
        assertEquals(30f, dragTranslation(pickUpStart = 98, distance = 30f, laidOutStart = 98))
        // After a swap the tile is laid out one slot on; the translation shrinks to match.
        assertEquals(-68f, dragTranslation(pickUpStart = 98, distance = 30f, laidOutStart = 196))
        // After auto-scrolling 40px the same slot sits 40px further left.
        assertEquals(70f, dragTranslation(pickUpStart = 98, distance = 30f, laidOutStart = 58))
    }

    @Test
    fun autoScrollStepOnlyPastTheEdges() {
        val maxStep = 14f
        assertEquals(0f, autoScrollStep(start = 0f, size = 90, viewportEnd = 360f, maxStep = maxStep))
        assertEquals(0f, autoScrollStep(start = 270f, size = 90, viewportEnd = 360f, maxStep = maxStep))
        assertEquals(-5f, autoScrollStep(start = -20f, size = 90, viewportEnd = 360f, maxStep = maxStep))
        assertEquals(5f, autoScrollStep(start = 290f, size = 90, viewportEnd = 360f, maxStep = maxStep))
        // clamped
        assertEquals(-14f, autoScrollStep(start = -200f, size = 90, viewportEnd = 360f, maxStep = maxStep))
        assertEquals(14f, autoScrollStep(start = 400f, size = 90, viewportEnd = 360f, maxStep = maxStep))
        // under half a pixel: quiet
        assertEquals(0f, autoScrollStep(start = -1.9f, size = 90, viewportEnd = 360f, maxStep = maxStep))
        assertEquals(0f, autoScrollStep(start = 271.9f, size = 90, viewportEnd = 360f, maxStep = maxStep))
        assertEquals(-0.5f, autoScrollStep(start = -2f, size = 90, viewportEnd = 360f, maxStep = maxStep))
    }

    @Test
    fun layoutGateBlocksUntilTheMoveIsLaidOut() {
        val gate = LayoutGate()
        assertFalse(gate.blocks(1)) // idle

        gate.arm(1) // asked to move the tile at index 1
        assertTrue(gate.blocks(1)) // stale layout: no inverse move
        assertTrue(gate.blocks(1))
        assertFalse(gate.blocks(2)) // laid out at its new slot: next swap may go
        assertFalse(gate.blocks(2)) // and the gate stays open
    }

    @Test
    fun layoutGateReleasesAfterThirtyChecks() {
        val gate = LayoutGate()
        gate.arm(0)
        repeat(30) { assertTrue("check ${it + 1}", gate.blocks(0)) }
        assertFalse(gate.blocks(0)) // the move was refused; stop waiting
        assertFalse(gate.blocks(0))
    }

    @Test
    fun layoutGateResetsOnANewDrag() {
        val gate = LayoutGate()
        gate.arm(0)
        gate.reset()
        assertFalse(gate.blocks(0))
        gate.arm(2)
        repeat(29) { gate.blocks(2) }
        gate.arm(3) // a fresh request restarts the valve
        repeat(30) { assertTrue(gate.blocks(3)) }
        assertFalse(gate.blocks(3))
    }

    // endregion
}
