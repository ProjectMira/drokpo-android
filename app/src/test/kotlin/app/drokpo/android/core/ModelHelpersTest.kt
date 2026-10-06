package app.drokpo.android.core

import app.drokpo.android.core.model.CommentCard
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.FeedItem
import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.LastMessage
import app.drokpo.android.core.model.LikedContent
import app.drokpo.android.core.model.Match
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Poll
import app.drokpo.android.core.model.PollOption
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.ProfileQuestion
import app.drokpo.android.core.model.SentMessage
import app.drokpo.android.core.model.Socials
import app.drokpo.android.core.model.SwipeEntry
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.core.model.AdCard
import app.drokpo.android.core.model.abbreviatedRelativeString
import app.drokpo.android.core.model.ageInYears
import app.drokpo.android.core.model.firestoreInstant
import app.drokpo.android.core.model.firestoreInt
import app.drokpo.android.core.model.firestoreIntMap
import app.drokpo.android.core.model.firestoreMap
import app.drokpo.android.core.model.firestoreStringList
import app.drokpo.android.core.model.parseIso8601
import app.drokpo.android.core.model.parsePublishedDate
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ModelHelpersTest {
    private val utc = ZoneOffset.UTC

    @Test
    fun ageCountsWholeYears() {
        val now = Instant.parse("2026-10-06T12:00:00Z")
        assertEquals(28, ageInYears("1998-04-12", now, utc))
        assertEquals(27, ageInYears("1998-10-07", now, utc)) // birthday tomorrow
        assertEquals(28, ageInYears("1998-10-06", now, utc)) // birthday today
        assertNull(ageInYears(null, now, utc))
        assertNull(ageInYears("12/04/1998", now, utc))
        assertNull(ageInYears("1998-04-12T00:00:00", now, utc))
        // Birth instant is UTC midnight, counted in the local zone: in UTC-5 the
        // birthday rolls over at 19:00 the evening before.
        val newYork = java.time.ZoneId.of("America/New_York")
        assertEquals(28, ageInYears("1998-10-07", Instant.parse("2026-10-07T00:30:00Z"), newYork))
    }

    @Test
    fun profileHelpers() {
        val profile = Profile(
            uid = "u1",
            displayName = "Tenzin",
            dob = "1990-01-01",
            region = "India",
            socials = Socials(instagram = "t"),
            photos = listOf(Photo("p", 0, "https://x")),
            discoverable = false,
        )
        val card = profile.asFeedCard
        assertEquals("u1", card.uid)
        assertEquals("Tenzin", card.displayName)
        assertEquals(profile.age, card.age)
        assertEquals("1990-01-01", card.dob)
        assertEquals(profile.photos, card.photos)
        assertNull(card.kind)
        assertNull(card.distanceKm)
        assertEquals("me", Profile().asFeedCard.uid)
        assertEquals("me", Profile().id)

        assertEquals("1998-04-12", Profile.formatDob(Instant.parse("1998-04-12T00:00:00Z")))
        assertEquals("1998-04-12", Profile.dobFormatter.format(Instant.parse("1998-04-12T23:59:59Z")))
        assertEquals(Instant.parse("1998-04-12T00:00:00Z"), Profile.parseDobDate("1998-04-12"))
        assertNull(Profile.parseDobDate("1998-02-30"))
    }

    @Test
    fun feedCardDisplayAgePrefersServerAge() {
        assertEquals(31, FeedCard(uid = "a", age = 31, dob = "2000-01-01").displayAge)
        assertEquals(ageInYears("2000-01-01"), FeedCard(uid = "a", dob = "2000-01-01").displayAge)
        assertNull(FeedCard(uid = "a").displayAge)
        assertTrue(FeedCard(uid = "a", kind = "community").isCommunity)
        assertFalse(FeedCard(uid = "a", kind = "person").isCommunity)
    }

    @Test
    fun adDisplayPhotosPreferPhotos() {
        val withBoth = AdCard(adId = "a", imageUrl = "https://img", photos = listOf(Photo("x")))
        assertEquals(listOf(Photo("x")), withBoth.displayPhotos)
        val emptyPhotos = AdCard(adId = "a", imageUrl = "https://img", photos = emptyList())
        assertEquals(listOf(Photo("ad-image-a", 0, "https://img")), emptyPhotos.displayPhotos)
        assertEquals(emptyList<Photo>(), AdCard(adId = "a").displayPhotos)
    }

    @Test
    fun iso8601MatchesSwiftFormatterPair() {
        assertEquals(Instant.parse("2026-08-15T12:30:00Z"), parseIso8601("2026-08-15T18:00:00+05:30"))
        assertEquals(Instant.parse("2026-10-06T12:34:56Z"), parseIso8601("2026-10-06T12:34:56Z"))
        assertEquals(Instant.parse("2026-10-06T12:34:56.123456Z"), parseIso8601("2026-10-06T12:34:56.123456+00:00"))
        assertEquals(Instant.parse("2026-10-06T12:34:56.500Z"), parseIso8601("2026-10-06T12:34:56.5+00:00"))
        assertNull(parseIso8601("2026-10-06T12:34:56")) // no zone
        assertNull(parseIso8601("2026-10-06"))
        assertNull(parseIso8601("not a date"))
    }

    @Test
    fun publishedDateFallbacks() {
        assertEquals(Instant.parse("2026-07-13T03:00:00Z"), parsePublishedDate("2026-07-13T08:30:00+05:30", utc))
        assertEquals(Instant.parse("2026-07-13T08:30:00Z"), parsePublishedDate("2026-07-13T08:30:00", utc))
        assertEquals(Instant.parse("2026-07-13T00:00:00Z"), parsePublishedDate("2026-07-13", utc))
        val kolkata = java.time.ZoneId.of("Asia/Kolkata")
        assertEquals(Instant.parse("2026-07-12T18:30:00Z"), parsePublishedDate("2026-07-13", kolkata))
        // Swift's default ISO8601DateFormatter rejects fractional seconds, and the
        // DateFormatter fallbacks don't match them either.
        assertNull(parsePublishedDate("2026-07-13T08:30:00.000Z", utc))
        assertNull(parsePublishedDate("yesterday", utc))
        assertNull(NewsCard(newsId = "n").publishedDate)
        assertNull(NewsCard(newsId = "n", publishedAt = "junk").relativePublished)
    }

    @Test
    fun relativeStringsMatchAbbreviatedFormatter() {
        val now = Instant.parse("2026-10-06T12:00:00Z")
        fun ago(d: Duration) = abbreviatedRelativeString(now.minus(d), now, utc)
        assertEquals("30s ago", ago(Duration.ofSeconds(30)))
        assertEquals("5m ago", ago(Duration.ofMinutes(5)))
        assertEquals("3h ago", ago(Duration.ofHours(3)))
        assertEquals("1d ago", ago(Duration.ofHours(36)))
        assertEquals("2d ago", ago(Duration.ofDays(2)))
        assertEquals("1w ago", ago(Duration.ofDays(10)))
        assertEquals("1mo ago", ago(Duration.ofDays(40)))
        assertEquals("1y ago", ago(Duration.ofDays(400)))
        assertEquals("in 2d", abbreviatedRelativeString(now.plus(Duration.ofDays(2)), now, utc))
        assertEquals("in 0s", abbreviatedRelativeString(now, now, utc))
    }

    @Test
    fun postHelpers() {
        val event = CommunityPostCard(postId = "p", kind = "event", eventAt = "2026-12-15T18:00:00+05:30")
        assertEquals(Instant.parse("2026-12-15T12:30:00Z"), event.eventDate)
        assertNull(CommunityPostCard(postId = "p", eventAt = "next friday").eventDate)
        assertEquals("p", event.id)
        assertEquals(emptyList<Photo>(), event.displayPhotos)
    }

    @Test
    fun pollMath() {
        val poll = Poll(listOf(PollOption("a", "A"), PollOption("b", "B")), mapOf("a" to 3, "b" to 1))
        assertEquals(4, poll.totalVotes)
        assertEquals(0.75, poll.percentage("a"), 1e-9)
        assertEquals(0.25, poll.percentage("b"), 1e-9)
        assertEquals(0.0, poll.percentage("missing"), 0.0)
        val empty = Poll(listOf(PollOption("a", "A")), emptyMap())
        assertEquals(0, empty.totalVotes)
        assertEquals(0.0, empty.percentage("a"), 0.0)
    }

    @Test
    fun communityVerification() {
        assertTrue(CommunityProfile(verification = "verified").isVerified)
        assertFalse(CommunityProfile(verification = "verified").isPending)
        assertTrue(CommunityProfile(verification = "pending").isPending)
        assertTrue(CommunityProfile().isPending)
        assertFalse(CommunityProfile(verification = "rejected").isPending)
        assertFalse(CommunityProfile(verification = "rejected").isVerified)
        assertEquals("community", CommunityProfile().id)
    }

    @Test
    fun commentHelpers() {
        val comment = CommentCard(
            commentId = "c1",
            authorKind = "community",
            authorPhotoUrl = "https://p",
            parentId = "top",
            createdAt = Instant.now().minus(Duration.ofHours(3)).toString(),
        )
        assertEquals(Photo(storagePath = "comment-author-c1", url = "https://p"), comment.authorPhoto)
        assertTrue(comment.isCommunityAuthor)
        assertFalse(comment.isTopLevel)
        assertEquals("3h ago", comment.relativeCreated)
        assertNull(CommentCard(commentId = "c2").authorPhoto)
        assertNull(CommentCard(commentId = "c2", createdAt = "2026-10-06T12:00:00").relativeCreated)
        assertTrue(CommentCard(commentId = "c2").isTopLevel)
    }

    @Test
    fun identityFallbacksAreStablePerInstance() {
        assertEquals("m", Match(matchId = "m").id)
        assertEquals("u", Match(otherUser = FeedCard(uid = "u")).id)
        val anonymous = Match()
        assertEquals(anonymous.id, anonymous.id)
        assertNotEquals(Match().id, Match().id)

        assertEquals("u", SwipeEntry(uid = "u").id)
        assertEquals("o", SwipeEntry(otherUser = FeedCard(uid = "o")).id)
        val swipe = SwipeEntry()
        assertEquals(swipe.id, swipe.id)

        assertEquals("m1", SentMessage(messageId = "m1").id)
        val sent = SentMessage()
        assertEquals(sent.id, sent.id)
        // The fallback never leaks into equality.
        assertEquals(Match(), Match())
    }

    @Test
    fun feedAndLikedIds() {
        assertEquals("person-u", FeedItem.Person(FeedCard(uid = "u")).id)
        assertEquals("ad-a", FeedItem.Ad(AdCard(adId = "a")).id)
        assertEquals("news-n", FeedItem.News(NewsCard(newsId = "n")).id)
        assertEquals("post-p", FeedItem.Post(CommunityPostCard(postId = "p")).id)
        assertEquals("liked-news-n", LikedContent.News(NewsCard(newsId = "n"), null).id)
        assertEquals("liked-post-p", LikedContent.Post(CommunityPostCard(postId = "p"), "t").id)
        assertEquals("t", LikedContent.Post(CommunityPostCard(postId = "p"), "t").likedAt)
    }

    @Test
    fun vocabularyIsVerbatim() {
        assertEquals(listOf("male", "female"), Vocabulary.genders)
        assertEquals(listOf("India", "Nepal", "Bhutan", "North America", "Europe", "Australia", "Other"), Vocabulary.regions)
        assertEquals(Vocabulary.regions, Vocabulary.regionCoordinates.keys.toList())
        assertEquals(GeoLocation(-33.87, 151.21), Vocabulary.regionCoordinates["Australia"])
        assertEquals(24, Vocabulary.interests.size)
        assertEquals("Language exchange", Vocabulary.interests.last())
        assertEquals(7, Vocabulary.educationLevels.size)
        assertEquals(
            listOf("lookingFor", "teaChoice", "travelledTo", "favoriteMovies", "favoriteMusic", "perfectWeekend"),
            Vocabulary.questions.map { it.id },
        )
        val travelled = Vocabulary.questions[2].kind as ProfileQuestion.Kind.Text
        assertEquals("Dharamshala, Kathmandu, New York…", travelled.placeholder)
        val looking = Vocabulary.questions[0].kind as ProfileQuestion.Kind.Choice
        assertEquals(listOf("New friends", "Dating", "Friends first, then who knows", "Community & events"), looking.options)
        assertEquals(listOf("Fake profile", "Inappropriate photos", "Harassment", "Spam", "Underage", "Other"), Vocabulary.reportReasons)
    }

    @Test
    fun firestoreMatchDoc() {
        val created = Timestamp(1_760_000_000L, 0)
        val data: Map<String, Any?> = mapOf(
            "users" to listOf("u1", "u2"),
            "status" to "active",
            "createdAt" to created,
            "lastMessage" to mapOf("text" to "hi", "senderId" to "u2", "createdAt" to Timestamp(1_760_000_100L, 500)),
            // Android's Firestore SDK returns integers as Long.
            "unreadCount" to mapOf("u1" to 3L, "u2" to 0L),
        )
        val match = Match.fromFirestore("u1_u2", data)
        assertEquals("u1_u2", match.id)
        assertEquals(listOf("u1", "u2"), match.users)
        assertEquals("active", match.status)
        assertEquals(
            LastMessage(text = "hi", senderId = "u2", createdAt = Instant.ofEpochSecond(1_760_000_100L, 500).toString()),
            match.lastMessage,
        )
        // Chat-list order: last message time, else match creation, else the distant past.
        assertEquals(Instant.ofEpochSecond(1_760_000_100L, 500), match.sortDate)
        assertEquals(
            Instant.ofEpochSecond(1_760_000_000L),
            Match.fromFirestore("m", data - "lastMessage").sortDate,
        )
        assertEquals(Instant.EPOCH, Match.fromFirestore("m", emptyMap()).sortDate)
        assertEquals(3, match.unread("u1"))
        assertEquals(Instant.ofEpochSecond(1_760_000_000L), parseIso8601(match.createdAt!!))
        assertNull(match.otherUser)

        assertNull(LastMessage.fromFirestore(null))
        assertNull(Match.fromFirestore("x", mapOf("lastMessage" to null)).lastMessage)

        assertEquals(
            Instant.ofEpochSecond(1_760_000_100L, 500),
            data.firestoreMap("lastMessage")?.firestoreInstant("createdAt"),
        )
        assertEquals(7, mapOf<String, Any?>("n" to 7L).firestoreInt("n"))
        assertNull(mapOf<String, Any?>("n" to "7").firestoreInt("n"))
        assertNull(mapOf<String, Any?>("m" to mapOf("a" to "x")).firestoreIntMap("m"))
        assertNull(mapOf<String, Any?>("l" to listOf("a", 1)).firestoreStringList("l"))
        assertEquals(Instant.ofEpochSecond(1_760_000_000L), data.firestoreInstant("createdAt"))
        assertNull(data.firestoreInstant("status"))
    }
}
