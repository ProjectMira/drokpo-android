package app.drokpo.android.features.shared.community

import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.features.shared.news.newsBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class CommunityFormattingTest {
    private val post = CommunityPostCard(postId = "p1")

    @Test
    fun memberCountPluralises() {
        assertEquals("0 members", memberCountLabel(0))
        assertEquals("1 member", memberCountLabel(1))
        assertEquals("128 members", memberCountLabel(128))
    }

    @Test
    fun voteAndGoingLabels() {
        assertEquals("1 vote", voteCountLabel(1))
        assertEquals("26 votes", voteCountLabel(26))
        assertEquals("0 going", goingLabel(0))
        assertEquals("12 going", goingLabel(12))
    }

    @Test
    fun pollPercentRoundsHalfAwayFromZeroLikeSwift() {
        assertEquals("13%", pollPercentLabel(0.125)) // Kotlin round() would give 12
        assertEquals("33%", pollPercentLabel(1.0 / 3))
        assertEquals("67%", pollPercentLabel(2.0 / 3))
        assertEquals("0%", pollPercentLabel(0.0))
        assertEquals("100%", pollPercentLabel(1.0))
    }

    @Test
    fun commentButtonShowsCountOnlyWhenPositive() {
        assertEquals("Comment", commentButtonLabel(null))
        assertEquals("Comment", commentButtonLabel(0))
        assertEquals("4", commentButtonLabel(4))
    }

    @Test
    fun ctaFallsBackToLearnMore() {
        assertEquals("Learn more", ctaLabel(post))
        assertEquals("Learn more", ctaLabel(post.copy(ctaLabel = "")))
        assertEquals("Sign up", ctaLabel(post.copy(ctaLabel = "Sign up")))
    }

    @Test
    fun hasLinkMirrorsPostUrl() {
        assertFalse(hasLink(post))
        assertFalse(hasLink(post.copy(linkUrl = "  ")))
        assertTrue(hasLink(post.copy(linkUrl = "https://example.org")))
    }

    @Test
    fun websiteShownForAnyNonEmptyString() {
        assertNull(websiteUrl(null))
        assertNull(websiteUrl(CommunityProfile(website = null)))
        assertNull(websiteUrl(CommunityProfile(website = "")))
        assertEquals("example.org", websiteUrl(CommunityProfile(website = "example.org")))
    }

    @Test
    fun membersLinkForOwnerOrJoinedVisitorOnly() {
        assertTrue(showsMembersLink(ownerMode = true, community = null))
        assertTrue(showsMembersLink(ownerMode = false, community = CommunityProfile(joined = true)))
        assertFalse(showsMembersLink(ownerMode = false, community = CommunityProfile(joined = false)))
        assertFalse(showsMembersLink(ownerMode = false, community = CommunityProfile(joined = null)))
        assertFalse(showsMembersLink(ownerMode = false, community = null))
    }

    @Test
    fun communitiesDontJoinCommunities() {
        assertEquals(PageAction.NewPost, pageAction(ownerMode = true, isCommunityAccount = true))
        assertEquals(PageAction.Join, pageAction(ownerMode = false, isCommunityAccount = false))
        assertEquals(PageAction.None, pageAction(ownerMode = false, isCommunityAccount = true))
    }

    @Test
    fun postKindMapping() {
        assertEquals(PostKind.Link, postKind("link"))
        assertEquals(PostKind.Poll, postKind("poll"))
        assertEquals(PostKind.Event, postKind("event"))
        assertEquals(PostKind.Announcement, postKind("announcement"))
        assertEquals(PostKind.Announcement, postKind(null))
        assertEquals(PostKind.Announcement, postKind("something-new"))
    }

    @Test
    fun eventDateMatchesIosAbbreviatedShortened() {
        val zone = ZoneId.of("America/Toronto")
        val instant = LocalDateTime.of(2026, 10, 12, 18, 0).atZone(zone).toInstant()
        // CLDR puts a narrow no-break space before AM/PM; compare with plain spaces.
        fun normalized(s: String) = s.replace(' ', ' ').replace(' ', ' ')

        assertEquals("Oct 12, 2026 at 6:00 PM", normalized(formatEventDate(instant, zone, Locale.US)))
        assertEquals("Oct 12, 2026 at 6:00 PM", normalized(formatEventDate(instant, zone, Locale.US, is24Hour = false)))
        assertEquals("Oct 12, 2026 at 18:00", normalized(formatEventDate(instant, zone, Locale.US, is24Hour = true)))
        // A 24-hour locale keeps its own clock unless the device forces 12-hour.
        val german = normalized(formatEventDate(instant, zone, Locale.GERMANY))
        assertTrue(german, german.endsWith(", 18:00"))
    }

    @Test
    fun newsBodyPrefersSummaryThenGist() {
        val news = NewsCard(newsId = "n1", gist = "Gist", summary = "Summary")
        assertEquals("Summary", newsBody(news))
        assertEquals("Gist", newsBody(news.copy(summary = "")))
        assertEquals("Gist", newsBody(news.copy(summary = null)))
        assertEquals("", newsBody(news.copy(summary = null, gist = null)))
    }
}
