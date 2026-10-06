package app.drokpo.android.core

import app.drokpo.android.core.model.AccountResponse
import app.drokpo.android.core.model.CommentCard
import app.drokpo.android.core.model.CommentIn
import app.drokpo.android.core.model.CommentVoteIn
import app.drokpo.android.core.model.CommentVoteResult
import app.drokpo.android.core.model.FcmTokenIn
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.PhotoOrderUpdate
import app.drokpo.android.core.model.ProfileUpdate
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.SwipeEntry
import app.drokpo.android.core.model.TolerantList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ApiClientTest {
    private lateinit var server: MockWebServer
    private var token: String? = "test-id-token"
    private lateinit var api: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ApiClient(baseUrl = server.url("/").toString(), tokenProvider = { token })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(code: Int = 200, body: String = """{"ok":true}""") {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun taken(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private suspend inline fun <reified E : Throwable> expectFailure(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("Expected ${E::class.simpleName}, got $e", e)
        }
        fail("Expected ${E::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun getSendsBearerTokenAndNoCacheAndDecodes() = runTest {
        respond(body = Fixtures.ACCOUNT_PERSON)
        val account = api.get<AccountResponse>("/api/account")
        assertEquals("person", account.accountType)
        assertEquals("Tenzin", account.profile?.displayName)

        val request = taken()
        assertEquals("GET", request.method)
        assertEquals("/api/account", request.path)
        assertEquals("Bearer test-id-token", request.getHeader("Authorization"))
        assertEquals("no-cache", request.getHeader("Cache-Control"))
        assertNull(request.getHeader("Content-Type"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun queryItemsAreEncoded() = runTest {
        respond(body = Fixtures.SWIPES)
        val swipes = api.get<TolerantList<SwipeEntry>>("/api/swipes/received", listOf("action" to "like", "limit" to "100"))
        assertEquals(3, swipes.items.size)
        val request = taken()
        assertEquals("/api/swipes/received?action=like&limit=100", request.path)

        respond()
        api.delete<EmptyResponse>("/api/profile/me/photos", listOf("storage_path" to "users/u1/photos/a b+c.jpg"))
        val delete = taken()
        assertEquals("DELETE", delete.method)
        assertEquals("users/u1/photos/a b+c.jpg", delete.requestUrl?.queryParameter("storage_path"))
        assertEquals("/api/profile/me/photos", delete.requestUrl?.encodedPath)
        assertEquals(0L, delete.bodySize)

        respond()
        api.delete<EmptyResponse>("/api/profile/me/fcm-tokens", listOf("token" to "abc:DEF/123=="))
        assertEquals("abc:DEF/123==", taken().requestUrl?.queryParameter("token"))
    }

    @Test
    fun noQueryMeansNoQuestionMark() = runTest {
        respond()
        api.get<EmptyResponse>("/api/communities/home")
        assertEquals("/api/communities/home", taken().path)
    }

    @Test
    fun bodiesAreJsonWithoutNulls() = runTest {
        respond()
        api.post<EmptyResponse>("/api/profile/me/fcm-tokens", FcmTokenIn("fcm-123"))
        val post = taken()
        assertEquals("POST", post.method)
        assertEquals("application/json", post.getHeader("Content-Type"))
        assertEquals("""{"token":"fcm-123"}""", post.body.readUtf8())

        respond()
        api.patch<EmptyResponse>("/api/profile/me", ProfileUpdate(discoverable = false))
        val patch = taken()
        assertEquals("PATCH", patch.method)
        assertEquals("""{"discoverable":false}""", patch.body.readUtf8())

        respond()
        api.patch<EmptyResponse>("/api/profile/me/photos/order", PhotoOrderUpdate(listOf("b", "a")))
        assertEquals("""{"storagePaths":["b","a"]}""", taken().body.readUtf8())

        respond(body = Fixtures.COMMENT_VOTE)
        val vote = api.put<CommentVoteResult>("/api/posts/p1/comments/cm1/vote", CommentVoteIn("like"))
        assertEquals(4, vote.likeCount)
        val put = taken()
        assertEquals("PUT", put.method)
        assertEquals("""{"value":"like"}""", put.body.readUtf8())

        respond(body = Fixtures.COMMENT)
        val created = api.post<CommentCard>("/api/posts/p1/comments", CommentIn(text = "Count me in!"))
        assertEquals("cm1", created.commentId)
        assertEquals("""{"text":"Count me in!"}""", taken().body.readUtf8())

        respond()
        api.post<EmptyResponse>("/api/raw", buildJsonObject { put("a", JsonPrimitive(1)) })
        assertEquals("""{"a":1}""", taken().body.readUtf8())
    }

    @Test
    fun bodylessPostAndPutSendEmptyBodies() = runTest {
        respond(body = Fixtures.RSVP_RESULT)
        val rsvp = api.post<RsvpResult>("/api/posts/p2/rsvp")
        assertEquals(true, rsvp.going)
        val post = taken()
        assertEquals("POST", post.method)
        assertEquals(0L, post.bodySize)
        assertNull(post.getHeader("Content-Type"))

        respond(body = Fixtures.NEWS)
        val news = api.put<NewsCard>("/api/news/n1/like")
        assertEquals("n1", news.newsId)
        val put = taken()
        assertEquals("PUT", put.method)
        assertEquals("/api/news/n1/like", put.path)
        assertEquals(0L, put.bodySize)
    }

    @Test
    fun httpErrorWithStringDetail() = runTest {
        respond(404, """{"detail":"Profile not found"}""")
        val error = expectFailure<ApiError.Http> { api.get<EmptyResponse>("/api/profile/me") }
        assertEquals(404, error.status)
        assertEquals("Profile not found", error.message)
        assertEquals("Profile not found", error.userMessage())
    }

    @Test
    fun httpErrorWithValidationDetailList() = runTest {
        respond(
            422,
            """{"detail":[
                {"type":"value_error","loc":["body","name"],"msg":"Value error, name must be 2-80 characters","input":"x"},
                {"type":"missing","loc":["body","email"],"msg":"Field required"},
                {"type":"weird","loc":["body"],"msg":42}
            ]}""",
        )
        val error = expectFailure<ApiError.Http> { api.post<EmptyResponse>("/api/communities/onboarding", FcmTokenIn("x")) }
        assertEquals(422, error.status)
        assertEquals("Value error, name must be 2-80 characters\nField required", error.message)
    }

    @Test
    fun httpErrorWithoutDetailFallsBackToStatus() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        val error = expectFailure<ApiError.Http> { api.get<EmptyResponse>("/api/feed") }
        assertEquals(502, error.status)
        assertEquals("", error.message)
        assertEquals("Server error (502).", error.userMessage())

        respond(500, """{"detail":{"code":"x"}}""")
        assertEquals("Server error (500).", expectFailure<ApiError.Http> { api.get<EmptyResponse>("/x") }.userMessage())

        respond(400, """{"detail":["not","objects"]}""")
        assertEquals("", expectFailure<ApiError.Http> { api.get<EmptyResponse>("/x") }.message)

        respond(409, """{"detail":"Community already exists"}""")
        assertEquals("Community already exists", expectFailure<ApiError> { api.post<EmptyResponse>("/x") }.userMessage())
    }

    @Test
    fun emptyResponseAcceptsAnyObject() = runTest {
        respond(body = """{"uid":"u1"}""")
        assertEquals(EmptyResponse, api.post<EmptyResponse>("/api/onboarding", FcmTokenIn("x")))
        respond(body = """{"postId":"p9"}""")
        assertEquals(EmptyResponse, api.post<EmptyResponse>("/api/communities/me/posts", FcmTokenIn("x")))
    }

    @Test
    fun notAuthenticatedWhenNoToken() = runTest {
        token = null
        val error = expectFailure<ApiError> { api.get<AccountResponse>("/api/account") }
        assertEquals(ApiError.NotAuthenticated, error)
        assertEquals("You need to sign in again.", error.userMessage())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun tokenProviderErrorsPropagate() = runTest {
        val throwing = ApiClient(baseUrl = server.url("/").toString(), tokenProvider = { throw ApiError.NotAuthenticated })
        assertEquals(ApiError.NotAuthenticated, expectFailure<ApiError> { throwing.get<EmptyResponse>("/api/account") })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun decodingFailureIsASerializationException() = runTest {
        respond(body = """{"accountType":7}""")
        val error = expectFailure<SerializationException> { api.get<AccountResponse>("/api/account") }
        assertEquals("The data couldn't be read because it isn't in the correct format.", error.userMessage())

        respond(body = """{"title":"no id"}""")
        val missing = expectFailure<SerializationException> { api.get<NewsCard>("/api/news/x") }
        assertEquals("The data couldn't be read because it is missing.", missing.userMessage())

        respond(body = "")
        expectFailure<SerializationException> { api.post<EmptyResponse>("/api/matches/m/read") }
    }

    @Test
    fun baseUrlWithoutTrailingSlash() = runTest {
        val noSlash = ApiClient(baseUrl = server.url("/").toString().trimEnd('/'), tokenProvider = { "t" })
        respond()
        noSlash.get<EmptyResponse>("/api/health")
        assertEquals("/api/health", taken().path)
    }

    @Test
    fun tryOrNullSwallowsFailures() = runTest {
        respond(500, """{"detail":"boom"}""")
        assertNull(tryOrNull { api.post<EmptyResponse>("/api/news/n1/events") })
        respond()
        assertEquals(EmptyResponse, tryOrNull { api.post<EmptyResponse>("/api/news/n1/events") })
    }

    @Test
    fun userMessagesMirrorFoundation() {
        assertEquals("Unexpected response from the server.", ApiError.InvalidResponse.userMessage())
        assertEquals("Server error (503).", ApiError.Http(503, "").userMessage())
        assertEquals("The Internet connection appears to be offline.", UnknownHostException("drokpo-backend.web.app").userMessage())
        assertEquals("The request timed out.", SocketTimeoutException("timeout").userMessage())
        assertEquals("Could not connect to the server.", ConnectException("refused").userMessage())
        assertEquals("The network connection was lost.", IOException("unexpected end of stream").userMessage())
        assertEquals("Something specific", IllegalStateException("Something specific").userMessage())
        assertTrue(RuntimeException().userMessage().isNotBlank())
    }
}
