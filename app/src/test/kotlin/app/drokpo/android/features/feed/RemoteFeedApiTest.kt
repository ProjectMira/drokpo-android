package app.drokpo.android.features.feed

import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.model.FeedItem
import app.drokpo.android.core.model.SwipeAction
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Same paths, methods, bodies and queries as iOS FeedModel. */
class RemoteFeedApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RemoteFeedApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = RemoteFeedApi(ApiClient(baseUrl = server.url("/").toString(), tokenProvider = { "token" }))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(body: String = """{"ok":true}""") {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun taken(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    @Test
    fun feedPageAsksForServerOrderedItems() = runTest {
        respond(
            """
            {"items": [
              {"type": "person", "data": {"uid": "u1", "displayName": "Pema"}},
              {"type": "somethingNew", "data": {}},
              {"type": "news", "data": {"newsId": "n1", "title": "Losar"}},
              {"type": "communityPost", "data": {"postId": "p1", "kind": "event"}},
              {"type": "ad", "data": {"adId": "a1"}}
            ]}
            """.trimIndent(),
        )
        val page = api.fetchPage()
        val request = taken()
        assertEquals("GET", request.method)
        assertEquals("/api/feed?limit=20&shape=items", request.path)
        // Unknown card kinds are skipped, not fatal.
        assertEquals(listOf("person-u1", "news-n1", "post-p1", "ad-a1"), page.items!!.map(FeedItem::id))
    }

    @Test
    fun swipeAndUndo() = runTest {
        respond("""{"matched": true, "matchId": "m1"}""")
        val result = api.swipe("u1", SwipeAction.like)
        assertTrue(result.isMatch)
        taken().let {
            assertEquals("POST", it.method)
            assertEquals("/api/swipes/u1", it.path)
            assertEquals("""{"action":"like"}""", it.body.readUtf8())
        }

        respond()
        api.undoSwipe("u1")
        taken().let {
            assertEquals("DELETE", it.method)
            assertEquals("/api/swipes/u1", it.path)
        }
    }

    @Test
    fun savingNewsAndPostsIsAPutLike() = runTest {
        respond("""{"newsId": "n1"}""")
        api.saveNews("n1")
        taken().let {
            assertEquals("PUT", it.method)
            assertEquals("/api/news/n1/like", it.path)
        }
        respond("""{"postId": "p1"}""")
        api.savePost("p1")
        taken().let {
            assertEquals("PUT", it.method)
            assertEquals("/api/posts/p1/like", it.path)
        }
    }

    @Test
    fun voteAndRsvp() = runTest {
        respond("""{"poll": {"options": [{"id": "o1", "label": "Yes"}], "counts": {"o1": 3}}, "myVote": "o1"}""")
        val vote = api.vote("p1", "o1")
        assertEquals("o1", vote.myVote)
        assertEquals(3, vote.poll?.totalVotes)
        taken().let {
            assertEquals("POST", it.method)
            assertEquals("/api/posts/p1/vote", it.path)
            assertEquals("""{"optionId":"o1"}""", it.body.readUtf8())
        }

        respond("""{"attendeeCount": 13, "going": true}""")
        assertEquals(13, api.rsvp("e1", going = true).attendeeCount)
        taken().let {
            assertEquals("POST", it.method)
            assertEquals("/api/posts/e1/rsvp", it.path)
        }

        respond("""{"attendeeCount": 12, "going": false}""")
        assertEquals(false, api.rsvp("e1", going = false).going)
        taken().let {
            assertEquals("DELETE", it.method)
            assertEquals("/api/posts/e1/rsvp", it.path)
        }
    }
}
