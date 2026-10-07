package app.drokpo.android.features.feed

import app.drokpo.android.core.model.AdCard
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.features.shared.sharing.ShareableContent
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class DeckLogicTest {
    private val person = FeedCard(uid = "u1", displayName = "Pema")
    private val community = FeedCard(uid = "c1", displayName = "TCV", kind = "community")
    private val ad = AdCard(adId = "a1")
    private val news = NewsCard(newsId = "n1")
    private val post = CommunityPostCard(postId = "p1", kind = "announcement")
    private val event = CommunityPostCard(postId = "e1", kind = "event")

    @Test
    fun deckIdsArePrefixedByKind() {
        assertEquals("profile-u1", DeckItem.Profile(person).id)
        assertEquals("ad-a1", DeckItem.Ad(ad).id)
        assertEquals("news-n1", DeckItem.News(news).id)
        assertEquals("post-p1", DeckItem.Post(post).id)
        assertEquals(person, DeckItem.Profile(person).profileCard)
        assertNull(DeckItem.Ad(ad).profileCard)
    }

    @Test
    fun likeLabelsPerKind() {
        assertEquals("LIKE", DeckItem.Profile(person).likeLabel)
        assertEquals("VISIT", DeckItem.Ad(ad).likeLabel)
        assertEquals("SAVE", DeckItem.News(news).likeLabel)
        assertEquals("SAVE", DeckItem.Post(post).likeLabel)
        assertEquals("JOIN", DeckItem.Post(event).likeLabel)
    }

    @Test
    fun shareContentMapsTheTopCardAndAdsAreNotShareable() {
        assertEquals(ShareableContent.Profile(person), DeckItem.Profile(person).shareContent)
        // A community account's person-shaped card shares as the community.
        assertEquals(ShareableContent.Community(cid = "c1", name = "TCV"), DeckItem.Profile(community).shareContent)
        assertEquals(ShareableContent.News(news), DeckItem.News(news).shareContent)
        assertEquals(ShareableContent.Post(post), DeckItem.Post(post).shareContent)
        assertNull(DeckItem.Ad(ad).shareContent)
    }

    @Test
    fun linksMustBeNonBlank() {
        assertNull(AdCard(adId = "a", linkUrl = null).link)
        assertNull(AdCard(adId = "a", linkUrl = "   ").link)
        assertEquals("https://x.example", AdCard(adId = "a", linkUrl = " https://x.example ").link)
        assertEquals("https://n.example", NewsCard(newsId = "n", sourceUrl = "https://n.example").link)
        assertNull(CommunityPostCard(postId = "p", linkUrl = "").link)
    }

    @Test
    fun releaseDecisionIsDistanceOnlyLikeIos() {
        val threshold = 110f
        assertEquals(true, swipeDecision(111f, threshold))
        assertEquals(false, swipeDecision(-111f, threshold))
        assertNull(swipeDecision(110f, threshold))
        assertNull(swipeDecision(-110f, threshold))
        // A short drag never commits, however fast it was flicked.
        assertNull(swipeDecision(40f, threshold))
        assertNull(swipeDecision(-60f, threshold))
        assertNull(swipeDecision(0f, threshold))
    }

    @Test
    fun stampsShowPastFortyEitherWay() {
        assertFalse(stampVisible(0f))
        assertFalse(stampVisible(40f))
        assertTrue(stampVisible(40.5f))
        assertTrue(stampVisible(500f))
        // The other direction's drag never shows this stamp.
        assertFalse(stampVisible(-200f))
    }

    @Test
    fun rotationIsTheOffsetInDpOverEighteen() {
        assertEquals(0f, cardRotation(0f, density = 3f))
        assertEquals(110f / 18f, cardRotation(330f, density = 3f), 0.0001f)
        assertEquals(-10f, cardRotation(-180f, density = 1f), 0.0001f)
    }

    @Test
    fun aDraggedCardSwingsOnAnArc() {
        // iOS offset-then-rotate: at the 110 threshold the card has also dropped ~12.
        val right = arcTranslation(Offset(110f, 0f), cardRotation(110f, density = 1f))
        assertEquals(109.4f, right.x, 0.1f)
        assertEquals(11.7f, right.y, 0.1f)
        // Dragged left it drops too (mirror image).
        val left = arcTranslation(Offset(-110f, 0f), cardRotation(-110f, density = 1f))
        assertEquals(-109.4f, left.x, 0.1f)
        assertEquals(11.7f, left.y, 0.1f)
        // At rest nothing moves.
        assertEquals(Offset.Zero, arcTranslation(Offset.Zero, 0f))
    }

    @Test
    fun offDeckOnlyOnceNoPartOfTheRotatedCardOverlaps() {
        val w = 411f
        val h = 686f
        assertFalse(isOffDeck(Offset.Zero, density = 1f, width = w, height = h))
        assertFalse(isOffDeck(Offset(300f, 0f), density = 1f, width = w, height = h))
        val distance = flyOffDistance(w, h, density = 1f)
        // A phone-shaped deck is left through the bottom corner well before
        // width + height / 2 (the old level-flight estimate)…
        assertTrue(distance < w + h / 2f)
        assertTrue(isOffDeck(Offset(distance, 0f), density = 1f, width = w, height = h))
        assertTrue(isOffDeck(Offset(-distance, 0f), density = 1f, width = w, height = h))
        // …and not a step before.
        assertFalse(isOffDeck(Offset(distance - 4f, 0f), density = 1f, width = w, height = h))
        // Density only rescales: the same deck in px at 3x leaves at 3x the offset.
        assertEquals(distance * 3f, flyOffDistance(w * 3f, h * 3f, density = 3f), 12f)
    }

    @Test
    fun cardsBehindShrinkThreePercentPerSlot() {
        assertEquals(1f, depthScale(0f))
        assertEquals(0.97f, depthScale(1f), 0.0001f)
        assertEquals(0.94f, depthScale(2f), 0.0001f)
        assertEquals(0.985f, depthScale(0.5f), 0.0001f)
    }

    @Test
    fun eventDatesReadLikeIos() {
        val instant = Instant.parse("2026-10-12T18:00:00Z")
        // JDK 20+ CLDR puts a narrow no-break space before AM/PM.
        val english = formatEventDate(instant, ZoneOffset.UTC, Locale.US).replace(' ', ' ')
        assertEquals("Oct 12, 2026 at 6:00 PM", english)
        val german = formatEventDate(instant, ZoneOffset.UTC, Locale.GERMANY)
        assertEquals("12.10.2026, 18:00", german)
    }
}
