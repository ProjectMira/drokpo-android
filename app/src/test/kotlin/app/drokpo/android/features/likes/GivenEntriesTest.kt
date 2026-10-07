package app.drokpo.android.features.likes

import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.LikedContent
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.SwipeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GivenEntriesTest {
    private fun person(uid: String, createdAt: String?) =
        SwipeEntry(uid = uid, action = "like", createdAt = createdAt, otherUser = FeedCard(uid = uid, displayName = uid))

    private fun news(id: String, likedAt: String?) = LikedContent.News(NewsCard(newsId = id), likedAt)

    private fun post(id: String, likedAt: String?) = LikedContent.Post(CommunityPostCard(postId = id), likedAt)

    private val given = listOf(
        person("pema", "2026-10-06T10:00:00Z"),
        person("karma", "2026-10-01T09:00:00Z"),
        person("nodate", null),
    )
    private val content = listOf(
        news("losar", "2026-10-07T08:00:00Z"),
        post("event", "2026-10-06T12:00:00Z"),
        news("film", "2026-09-30T08:00:00Z"),
        post("poll", null),
    )

    @Test
    fun allMergesPeopleAndContentNewestFirstWithMissingDatesLast() {
        val ids = givenEntries(given, content, GivenFilter.All).map { it.id }
        assertEquals(
            listOf(
                "liked-news-losar",
                "liked-post-event",
                "person-pema",
                "person-karma",
                "liked-news-film",
                // Missing timestamps sink to the bottom, keeping their input order.
                "person-nodate",
                "liked-post-poll",
            ),
            ids,
        )
    }

    @Test
    fun friendsIsPeopleOnly() {
        val entries = givenEntries(given, content, GivenFilter.Friends)
        assertTrue(entries.all { it is GivenEntry.Person })
        assertEquals(listOf("person-pema", "person-karma", "person-nodate"), entries.map { it.id })
    }

    @Test
    fun communitiesIsSavedPostsOnly() {
        assertEquals(
            listOf("liked-post-event", "liked-post-poll"),
            givenEntries(given, content, GivenFilter.Communities).map { it.id },
        )
    }

    @Test
    fun newsIsSavedStoriesOnly() {
        assertEquals(
            listOf("liked-news-losar", "liked-news-film"),
            givenEntries(given, content, GivenFilter.News).map { it.id },
        )
    }

    @Test
    fun aLikedCommunityAccountCountsAsAFriendNotACommunityPost() {
        val community = SwipeEntry(
            uid = "c-tat",
            createdAt = "2026-10-05T00:00:00Z",
            otherUser = FeedCard(uid = "c-tat", kind = "community"),
        )
        assertEquals(listOf("person-c-tat"), givenEntries(listOf(community), emptyList(), GivenFilter.Friends).map { it.id })
        assertTrue(givenEntries(listOf(community), emptyList(), GivenFilter.Communities).isEmpty())
    }

    @Test
    fun emptyInputsGiveEmptyList() {
        GivenFilter.entries.forEach { filter ->
            assertTrue(givenEntries(emptyList(), emptyList(), filter).isEmpty())
        }
        assertTrue(givenEntries(given, emptyList(), GivenFilter.News).isEmpty())
    }

    @Test
    fun duplicateIdsKeepTheNewestCopy() {
        val entries = givenEntries(
            given = listOf(person("pema", "2026-10-01T00:00:00Z"), person("pema", "2026-10-06T00:00:00Z")),
            likedContent = listOf(news("losar", "2026-09-01T00:00:00Z"), news("losar", "2026-10-02T00:00:00Z")),
            filter = GivenFilter.All,
        )
        assertEquals(listOf("person-pema", "liked-news-losar"), entries.map { it.id })
        assertEquals("2026-10-06T00:00:00Z", entries[0].sortKey)
        assertEquals("2026-10-02T00:00:00Z", entries[1].sortKey)
    }

    @Test
    fun receivedRowsSkipEntriesWithoutACardAndRepeatedIds() {
        val rows = receivedRows(
            listOf(
                person("yangchen", "2026-10-06T00:00:00Z"),
                SwipeEntry(uid = "ghost", action = "like"),
                person("yangchen", "2026-10-05T00:00:00Z"),
                person("dechen", null),
            ),
        )
        assertEquals(listOf("yangchen", "dechen"), rows.map { it.id })
        assertEquals("yangchen", rows[0].card.uid)
    }

    @Test
    fun uiStateDerivesItsLists() {
        val state = LikesUiState(given = given, likedContent = content, givenFilter = GivenFilter.News)
        assertEquals(listOf("liked-news-losar", "liked-news-film"), state.givenEntries.map { it.id })
        assertEquals(Direction.Given, LikesUiState().direction)
        assertEquals(listOf("You liked", "Liked you"), Direction.entries.map { it.label })
        assertEquals(listOf("All", "Friends", "Communities", "News"), GivenFilter.entries.map { it.label })
    }
}
