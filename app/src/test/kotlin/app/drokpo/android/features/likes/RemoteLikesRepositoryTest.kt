package app.drokpo.android.features.likes

import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.LikedContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The six Likes endpoints, on the wire: method, path, query and body, plus response decoding. */
class RemoteLikesRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: RemoteLikesRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Same construction as core/ApiClientTest: a fake token and the mock server's URL.
        repository = RemoteLikesRepository(ApiClient(baseUrl = server.url("/").toString(), tokenProvider = { "test-id-token" }))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun taken(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private val swipes = """
        {"swipes": [
          {"uid": "u-yangchen", "action": "like", "createdAt": "2026-10-06T10:00:00Z",
           "otherUser": {"uid": "u-yangchen", "displayName": "Yangchen", "age": 29}},
          {"uid": "u-dechen", "action": "like", "matchId": "m-1",
           "otherUser": {"uid": "u-dechen", "displayName": "Dechen"}}
        ]}
    """.trimIndent()

    @Test
    fun receivedLikesGetsTheReceivedLikesOnly() = runTest {
        respond(swipes)
        val entries = repository.receivedLikes()

        val request = taken()
        assertEquals("GET", request.method)
        assertEquals("/api/swipes/received", request.requestUrl!!.encodedPath)
        assertEquals("like", request.requestUrl!!.queryParameter("action"))
        assertEquals(listOf("action"), request.requestUrl!!.queryParameterNames.toList())
        assertEquals("Bearer test-id-token", request.getHeader("Authorization"))
        // The repository returns everything; dropping matched people is the model's job.
        assertEquals(listOf("u-yangchen", "u-dechen"), entries.map { it.otherUser?.uid })
        assertEquals("m-1", entries[1].matchId)
    }

    @Test
    fun givenLikesGetsTheLikesYouSent() = runTest {
        respond(swipes)
        val entries = repository.givenLikes()

        val request = taken()
        assertEquals("GET", request.method)
        assertEquals("/api/swipes", request.requestUrl!!.encodedPath)
        assertEquals("like", request.requestUrl!!.queryParameter("action"))
        assertEquals(2, entries.size)
    }

    @Test
    fun likedContentDecodesNewsAndPostsInServerOrder() = runTest {
        respond(
            """
            {"items": [
              {"type": "news", "likedAt": "2026-10-07T08:00:00Z",
               "data": {"newsId": "n-losar", "title": "Losar", "sourceUrl": "https://example.org/losar"}},
              {"type": "communityPost", "likedAt": "2026-10-06T12:00:00Z",
               "data": {"postId": "p-event", "kind": "event", "title": "Losar party"}},
              {"type": "news", "data": {"newsId": "n-old"}}
            ]}
            """.trimIndent(),
        )
        val items = repository.likedContent()

        val request = taken()
        assertEquals("GET", request.method)
        assertEquals("/api/likes/content", request.requestUrl!!.encodedPath)
        assertNull(request.requestUrl!!.query)
        assertEquals(listOf("liked-news-n-losar", "liked-post-p-event", "liked-news-n-old"), items.map { it.id })
        assertEquals("Losar", (items[0] as LikedContent.News).item.title)
        assertEquals("event", (items[1] as LikedContent.Post).post.kind)
        assertNull(items[2].likedAt)
    }

    @Test
    fun aNullOrMissingItemsListIsNoSavedContent() = runTest {
        respond("""{"items": null}""")
        assertEquals(emptyList<LikedContent>(), repository.likedContent())
        respond("""{}""")
        assertEquals(emptyList<LikedContent>(), repository.likedContent())
    }

    @Test
    fun likeBackPostsALikeForThatPerson() = runTest {
        respond("""{"matched": true, "matchId": "m-yangchen"}""")
        val result = repository.likeBack("u-yangchen")

        val request = taken()
        assertEquals("POST", request.method)
        assertEquals("/api/swipes/u-yangchen", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(
            JsonObject(mapOf("action" to JsonPrimitive("like"))),
            Json.parseToJsonElement(request.body.readUtf8()),
        )
        assertTrue(result.isMatch)
        assertEquals("m-yangchen", result.matchId)
    }

    @Test
    fun likeBackWithoutAMatch() = runTest {
        respond("""{"matched": false, "matchId": null}""")
        assertFalse(repository.likeBack("u-pema").isMatch)
    }

    @Test
    fun unlikeNewsDeletesTheStoryLike() = runTest {
        respond("""{"ok": true}""")
        repository.unlikeNews("n-losar")

        val request = taken()
        assertEquals("DELETE", request.method)
        assertEquals("/api/news/n-losar/like", request.path)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun unlikePostDeletesThePostLike() = runTest {
        respond("""{"ok": true}""")
        repository.unlikePost("p-event")

        val request = taken()
        assertEquals("DELETE", request.method)
        assertEquals("/api/posts/p-event/like", request.path)
    }

    @Test
    fun aFailedDeleteSurfacesTheServerDetail() = runTest {
        respond("""{"detail": "Post not found"}""", code = 404)
        try {
            repository.unlikePost("p-gone")
            fail("Expected ApiError.Http")
        } catch (e: ApiError.Http) {
            assertEquals(404, e.status)
            assertEquals("Post not found", e.message)
        }
    }
}
