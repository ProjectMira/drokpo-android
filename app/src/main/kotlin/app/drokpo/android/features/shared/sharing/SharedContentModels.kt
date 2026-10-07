package app.drokpo.android.features.shared.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.VoteIn
import app.drokpo.android.core.model.VoteResult
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// State holders behind ShareDestinationSheet's loaders (iOS SharedUserLoader /
// SharedPostLoader / SharedNewsLoader / SharedPostView `@State`). Each lives in
// its NavHost entry's ViewModel scope, so a configuration change or a pop back
// to the loader keeps what was fetched (iOS `.task` runs once per view).

/** The REST calls the shared-content loaders make — a seam so JVM tests can fake them. */
internal interface SharedContentApi {
    /** GET /api/users/{uid} — person or community card (404 for deleted / blocked). */
    suspend fun user(uid: String): FeedCard

    /** GET /api/posts/{postId} — 404 for unpublished posts too. */
    suspend fun post(postId: String): CommunityPostCard

    /** GET /api/news/{newsId}. */
    suspend fun news(newsId: String): NewsCard

    /** POST /api/posts/{postId}/vote {optionId}. */
    suspend fun vote(postId: String, optionId: String): VoteResult

    /** going → POST /api/posts/{postId}/rsvp; not going → DELETE /api/posts/{postId}/rsvp. */
    suspend fun rsvp(postId: String, going: Boolean): RsvpResult
}

/** Production [SharedContentApi]: the same paths/methods/bodies as iOS. */
internal object DefaultSharedContentApi : SharedContentApi {
    override suspend fun user(uid: String): FeedCard = ApiClient.get("/api/users/$uid")

    override suspend fun post(postId: String): CommunityPostCard = ApiClient.get("/api/posts/$postId")

    override suspend fun news(newsId: String): NewsCard = ApiClient.get("/api/news/$newsId")

    override suspend fun vote(postId: String, optionId: String): VoteResult =
        ApiClient.post("/api/posts/$postId/vote", VoteIn(optionId = optionId))

    override suspend fun rsvp(postId: String, going: Boolean): RsvpResult =
        if (going) {
            ApiClient.post("/api/posts/$postId/rsvp")
        } else {
            ApiClient.delete("/api/posts/$postId/rsvp")
        }
}

/** Swift's `card == nil && !failed` / `card != nil` / `failed` triple as one value. */
internal sealed interface SharedLoad<out T> {
    data object Loading : SharedLoad<Nothing>

    data class Loaded<T>(val value: T) : SharedLoad<T>

    /**
     * Any failure — deleted account, unpublished post, a block relationship
     * (the backend 404s all of these alike), or no network. iOS shows the same
     * "Content unavailable" view for every case and never retries.
     */
    data object Failed : SharedLoad<Nothing>
}

/**
 * Fetches one shared item once (iOS loaders' `.task`): user cards and news
 * stories, which are read-only once shown.
 */
internal class SharedContentModel<T : Any>(
    private val fetch: suspend () -> T,
) : ViewModel() {
    private val _state = MutableStateFlow<SharedLoad<T>>(SharedLoad.Loading)
    val state: StateFlow<SharedLoad<T>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                SharedLoad.Loaded(fetch())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SharedLoad.Failed
            }
        }
    }
}

/**
 * A live community post reached through a share link (iOS SharedPostLoader +
 * SharedPostView): voting and RSVP update the local copy from the server's
 * answer — not optimistically, exactly like iOS — and failures surface as the
 * "Something went wrong" alert while the post stays as it was.
 */
internal class SharedPostModel(
    private val postId: String,
    private val api: SharedContentApi = DefaultSharedContentApi,
    /**
     * Where vote / RSVP requests run. iOS fires them in unstructured `Task`s that finish after the
     * sheet is dismissed; on viewModelScope, closing the sheet right after a tap would cancel the
     * OkHttp call and drop the vote. Null = AppGraph.appScope. (The load stays on viewModelScope.)
     */
    private val workScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<SharedLoad<CommunityPostCard>>(SharedLoad.Loading)
    val state: StateFlow<SharedLoad<CommunityPostCard>> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val scope: CoroutineScope get() = workScope ?: AppGraph.appScope

    init {
        viewModelScope.launch {
            _state.value = try {
                SharedLoad.Loaded(api.post(postId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SharedLoad.Failed
            }
        }
    }

    /** POST /api/posts/{post.postId}/vote — the loaded post's id, as iOS (no post yet → no-op, no UI). */
    fun vote(optionId: String) {
        val post = loadedPost() ?: return
        scope.launch {
            try {
                val result = api.vote(post.postId, optionId)
                updatePost { it.copy(poll = result.poll, myVote = result.myVote) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = e.userMessage()
            }
        }
    }

    /** POST / DELETE /api/posts/{post.postId}/rsvp. */
    fun rsvp(going: Boolean) {
        val post = loadedPost() ?: return
        scope.launch {
            try {
                val result = api.rsvp(post.postId, going)
                updatePost { it.copy(attendeeCount = result.attendeeCount, myRsvp = result.going) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = e.userMessage()
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    private fun loadedPost(): CommunityPostCard? = (_state.value as? SharedLoad.Loaded)?.value

    private fun updatePost(transform: (CommunityPostCard) -> CommunityPostCard) {
        _state.update { current -> if (current is SharedLoad.Loaded) SharedLoad.Loaded(transform(current.value)) else current }
    }
}
