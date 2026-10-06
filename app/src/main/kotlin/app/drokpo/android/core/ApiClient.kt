package app.drokpo.android.core

import app.drokpo.android.BuildConfig
import app.drokpo.android.core.model.asJsonDecoder
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Decodes successfully from any JSON object; used when the response body doesn't matter. */
@Serializable(with = EmptyResponseSerializer::class)
data object EmptyResponse

object EmptyResponseSerializer : KSerializer<EmptyResponse> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.drokpo.EmptyResponse")

    override fun deserialize(decoder: Decoder): EmptyResponse {
        // Swift's synthesized init(from:) on an empty struct still opens a
        // keyed container, so any object passes ({"ok": true}, {"uid": …})
        // but an array or bare value does not.
        val element = decoder.asJsonDecoder().decodeJsonElement()
        if (element !is JsonObject) throw SerializationException("Expected a JSON object, found $element")
        return EmptyResponse
    }

    override fun serialize(encoder: Encoder, value: EmptyResponse) {
        (encoder as? JsonEncoder)?.encodeJsonElement(JsonObject(emptyMap()))
            ?: throw SerializationException("EmptyResponse can only be encoded to JSON")
    }
}

/**
 * REST client for `https://drokpo-backend.web.app/api/…` — port of the iOS
 * `APIClient`. Every call carries the signed-in user's Firebase ID token as a
 * bearer token.
 *
 * Use the shared default (`ApiClient.get<T>(…)` or `ApiClient.shared.get<T>(…)`,
 * like Swift's `APIClient.shared`), or construct one with a fake token provider
 * and a MockWebServer base URL in tests.
 *
 * Request bodies are any `@Serializable` class instance (or a JsonElement);
 * responses decode into the reified `T` with [DrokpoJson]:
 *
 * ```
 * val account = ApiClient.get<AccountResponse>("/api/account")
 * ApiClient.post<EmptyResponse>("/api/profile/me/fcm-tokens", FcmTokenIn(token))
 * val list = ApiClient.get<TolerantList<SwipeEntry>>("/api/swipes", listOf("action" to "like"))
 * ```
 */
class ApiClient(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val tokenProvider: suspend () -> String? = firebaseTokenProvider,
    val client: OkHttpClient = defaultHttpClient,
    @PublishedApi internal val json: Json = DrokpoJson,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        request("GET", path, query, null, json.serializersModule.serializer<T>())

    suspend inline fun <reified T> post(path: String): T =
        request("POST", path, emptyList(), null, json.serializersModule.serializer<T>())

    suspend inline fun <reified T> post(path: String, body: Any): T =
        request("POST", path, emptyList(), encodeBody(body), json.serializersModule.serializer<T>())

    suspend inline fun <reified T> patch(path: String, body: Any): T =
        request("PATCH", path, emptyList(), encodeBody(body), json.serializersModule.serializer<T>())

    suspend inline fun <reified T> put(path: String): T =
        request("PUT", path, emptyList(), null, json.serializersModule.serializer<T>())

    suspend inline fun <reified T> put(path: String, body: Any): T =
        request("PUT", path, emptyList(), encodeBody(body), json.serializersModule.serializer<T>())

    suspend inline fun <reified T> delete(path: String, query: List<Pair<String, String>> = emptyList()): T =
        request("DELETE", path, query, null, json.serializersModule.serializer<T>())

    /** Encodes a request body with its own (plugin-generated) serializer. */
    @PublishedApi
    internal fun encodeBody(body: Any): String {
        if (body is JsonElement) return json.encodeToString(JsonElement.serializer(), body)
        // Reflective lookup of the generated `Companion.serializer()` (no
        // kotlin-reflect needed; kotlinx-serialization's bundled R8 rules keep it).
        val serializer: KSerializer<Any> = json.serializersModule.serializer(body.javaClass)
        return json.encodeToString(serializer, body)
    }

    @PublishedApi
    internal suspend fun <T> request(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        bodyJson: String?,
        deserializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val token = tokenProvider() ?: throw ApiError.NotAuthenticated

        val urlBuilder = base.newBuilder().addPathSegments(path.trimStart('/'))
        for ((name, value) in query) urlBuilder.addQueryParameter(name, value)

        val requestBody = when {
            bodyJson != null -> bodyJson.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE)
            // OkHttp requires a body for these methods; URLSession sent none.
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .method(method, requestBody)
            .header("Authorization", "Bearer $token")
            // Never serve API responses from a cache — see defaultHttpClient.
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(request).await().use { response ->
            val data = response.body.string()
            if (response.code !in 200 until 300) {
                throw ApiError.Http(status = response.code, message = errorMessage(data))
            }
            decode(deserializer, data)
        }
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, data: String): T =
        try {
            json.decodeFromString(deserializer, data)
        } catch (e: SerializationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // JsonElement casts inside custom serializers throw the plain kind;
            // surface every decoding failure uniformly (see userMessage()).
            throw SerializationException(e.message, e)
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * Never serve API responses from a cache. 404 is heuristically
         * cacheable, so URLSession.shared once replayed a cached 404 for
         * GET /profile/me after onboarding completed, trapping new users on
         * the onboarding screen. Authenticated JSON must always hit the
         * network: this client has no `Cache`, and every request also sends
         * `Cache-Control: no-cache`.
         */
        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .cache(null)
                // URLSession's default request timeout is 60s; OkHttp's 10s is
                // too tight for the cell networks many members are on.
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /**
         * `Auth.auth().currentUser` → `getIDToken()` (cached unless expired).
         * No signed-in user — or Firebase not configured at all — is
         * [ApiError.NotAuthenticated].
         */
        val firebaseTokenProvider: suspend () -> String? = {
            val auth = try {
                FirebaseAuth.getInstance()
            } catch (e: IllegalStateException) {
                throw ApiError.NotAuthenticated
            }
            val user = auth.currentUser ?: throw ApiError.NotAuthenticated
            user.getIdToken(false).await().token
        }

        /** The app-wide client (Swift `APIClient.shared`). */
        val shared: ApiClient by lazy { ApiClient() }

        suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
            shared.get<T>(path, query)

        suspend inline fun <reified T> post(path: String): T = shared.post<T>(path)

        suspend inline fun <reified T> post(path: String, body: Any): T = shared.post<T>(path, body)

        suspend inline fun <reified T> patch(path: String, body: Any): T = shared.patch<T>(path, body)

        suspend inline fun <reified T> put(path: String): T = shared.put<T>(path)

        suspend inline fun <reified T> put(path: String, body: Any): T = shared.put<T>(path, body)

        suspend inline fun <reified T> delete(path: String, query: List<Pair<String, String>> = emptyList()): T =
            shared.delete<T>(path, query)

        /**
         * FastAPI's `detail`: a string (HTTPException) or a list of
         * `{"msg": …}` validation errors joined by newlines; "" otherwise.
         */
        internal fun errorMessage(data: String): String {
            val root = try {
                DrokpoJson.parseToJsonElement(data)
            } catch (e: SerializationException) {
                return ""
            }
            val detail = (root as? JsonObject)?.get("detail") ?: return ""
            if (detail is JsonPrimitive && detail.isString) return detail.content
            // Swift: `json["detail"] as? [[String: Any]]` — every element must be an object.
            if (detail is JsonArray && detail.all { it is JsonObject }) {
                return detail.mapNotNull { element ->
                    val msg = (element as JsonObject)["msg"]
                    if (msg is JsonPrimitive && msg.isString) msg.content else null
                }.joinToString("\n")
            }
            return ""
        }
    }
}

/**
 * Swift's `try?` for suspending calls (`let _: EmptyResponse? = try? await …`):
 * any failure becomes null, but coroutine cancellation still propagates.
 */
inline fun <T> tryOrNull(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {
            // Cancellation is best-effort.
        }
    }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}
