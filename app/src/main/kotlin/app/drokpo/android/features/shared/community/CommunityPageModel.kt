package app.drokpo.android.features.shared.community

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.Safety
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityPostUpdate
import app.drokpo.android.core.model.CommunityPostsResponse
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.VoteIn
import app.drokpo.android.core.model.VoteResult
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The network calls CommunityPageScreen makes — an interface so JVM tests can inject a fake. */
internal interface CommunityPageRepository {
    /** GET /api/communities/{cid} (visitor header; 404s for an unverified community). */
    suspend fun community(cid: String): CommunityProfile

    /** GET /api/communities/{cid}/posts?limit=30[&before={postId}] — `posts ?? []`. */
    suspend fun posts(cid: String, before: String? = null): List<CommunityPostCard>

    /** POST /api/communities/{cid}/join */
    suspend fun join(cid: String)

    /** DELETE /api/communities/{cid}/join */
    suspend fun leave(cid: String)

    /** POST /api/posts/{postId}/vote {optionId} */
    suspend fun vote(postId: String, optionId: String): VoteResult

    /** POST (going) or DELETE (not going) /api/posts/{postId}/rsvp */
    suspend fun rsvp(postId: String, going: Boolean): RsvpResult

    /** PATCH /api/communities/me/posts/{postId} {active} */
    suspend fun setPostActive(postId: String, active: Boolean)

    /** POST /api/reports {reportedUid: cid, reason, note: ""} */
    suspend fun report(cid: String, reason: String)

    /** POST /api/blocks/{cid}, then remember it in BlockStore. */
    suspend fun block(cid: String, displayName: String?)
}

/** Production [CommunityPageRepository]: ApiClient + Safety, same paths/bodies as iOS. */
internal object ApiCommunityPageRepository : CommunityPageRepository {
    const val PAGE_SIZE = 30

    override suspend fun community(cid: String): CommunityProfile =
        ApiClient.get<CommunityProfile>("/api/communities/$cid")

    override suspend fun posts(cid: String, before: String?): List<CommunityPostCard> {
        val query = buildList {
            add("limit" to PAGE_SIZE.toString())
            if (before != null) add("before" to before)
        }
        return ApiClient.get<CommunityPostsResponse>("/api/communities/$cid/posts", query).posts ?: emptyList()
    }

    override suspend fun join(cid: String) {
        ApiClient.post<EmptyResponse>("/api/communities/$cid/join")
    }

    override suspend fun leave(cid: String) {
        ApiClient.delete<EmptyResponse>("/api/communities/$cid/join")
    }

    override suspend fun vote(postId: String, optionId: String): VoteResult =
        ApiClient.post<VoteResult>("/api/posts/$postId/vote", VoteIn(optionId = optionId))

    override suspend fun rsvp(postId: String, going: Boolean): RsvpResult =
        if (going) {
            ApiClient.post<RsvpResult>("/api/posts/$postId/rsvp")
        } else {
            ApiClient.delete<RsvpResult>("/api/posts/$postId/rsvp")
        }

    override suspend fun setPostActive(postId: String, active: Boolean) {
        ApiClient.patch<EmptyResponse>("/api/communities/me/posts/$postId", CommunityPostUpdate(active = active))
    }

    override suspend fun report(cid: String, reason: String) {
        Safety.report(reportedUid = cid, reason = reason, note = "")
    }

    override suspend fun block(cid: String, displayName: String?) {
        Safety.block(uid = cid, displayName = displayName)
    }
}

/** Everything CommunityPageModel owns (the iOS view's `@State`). */
@Immutable
internal data class CommunityPageUiState(
    /** Directory/rail preview first, then the fetched detail. Unused in owner mode. */
    val visitorCommunity: CommunityProfile? = null,
    val posts: List<CommunityPostCard> = emptyList(),
    val isLoadingHeader: Boolean = true,
    val isLoadingPosts: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMorePosts: Boolean = true,
    val isJoining: Boolean = false,
    /** Pull-to-refresh indicator (iOS `.refreshable` keeps its spinner up for the whole load()). */
    val isRefreshing: Boolean = false,
    /** The tapped tile, shown in CommunityPostDetailSheet; votes/RSVPs update it in place. */
    val selectedPost: CommunityPostCard? = null,
    val errorMessage: String? = null,
)

internal sealed interface CommunityPageEvent {
    /** iOS `dismiss()` after a successful block. */
    data object Dismiss : CommunityPageEvent
}

/**
 * State holder for CommunityPageScreen (port of CommunityPageView's `@State` + data functions).
 * Owner mode never fetches a header — the screen reads `session.myCommunity` instead, because
 * GET /communities/{cid} 404s for an unverified community (it isn't publicly listed yet).
 *
 * iOS waits for the server before changing joined/memberCount, votes and RSVPs (no optimistic
 * updates), so this does too.
 */
internal class CommunityPageModel(
    private val cid: String,
    private val ownerMode: Boolean,
    preview: CommunityProfile?,
    private val repository: CommunityPageRepository = ApiCommunityPageRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CommunityPageUiState(visitorCommunity = preview))
    val state: StateFlow<CommunityPageUiState> = _state.asStateFlow()

    private val _events = Channel<CommunityPageEvent>(Channel.BUFFERED)
    val events: Flow<CommunityPageEvent> = _events.receiveAsFlow()

    /**
     * Bumped on every join/leave; a load() started before the bump must not overwrite
     * joined/memberCount with its pre-toggle snapshot.
     */
    private var joinGeneration = 0

    /**
     * Bumped by every load(). Android-only guard: a pull-to-refresh (or the composer's reload)
     * can overlap the first load, and only the newest one may write — iOS lets whichever finishes
     * last win. Pages fetched by loadMore() for a list a reload has since replaced are dropped.
     */
    private var loadGeneration = 0

    init {
        // iOS `.task { await load() }`.
        load()
    }

    /** Reload header (visitor) and the first page of posts. */
    fun load(): Job = viewModelScope.launch { performLoad() }

    /** CommunityPostComposerSheet's `onSaved`: reload, and return once it's done. */
    suspend fun reloadAndWait() {
        load().join()
    }

    /** Pull to refresh. */
    fun refresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                performLoad()
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun performLoad() {
        val token = ++loadGeneration
        // Owner mode never fetches a header (it reads session.myCommunity, always already
        // populated by the time this screen exists) — resolve isLoadingHeader immediately so its
        // loading overlay can't get stuck.
        _state.update {
            it.copy(
                isLoadingPosts = true,
                hasMorePosts = true,
                isLoadingHeader = if (ownerMode) false else it.isLoadingHeader,
            )
        }
        try {
            if (!ownerMode) {
                _state.update { it.copy(isLoadingHeader = true) }
                val generationAtFetch = joinGeneration
                var result = repository.community(cid)
                if (token != loadGeneration) return
                if (generationAtFetch != joinGeneration) {
                    // A join/leave landed while this GET was in flight — keep the locally-updated
                    // membership state, take everything else.
                    val local = _state.value.visitorCommunity
                    result = result.copy(joined = local?.joined, memberCount = local?.memberCount)
                }
                _state.update { it.copy(visitorCommunity = result, isLoadingHeader = false) }
            }
            // distinctBy: the grid keys tiles by postId, and Compose lazy lists throw on a repeated
            // key (iOS ForEach only glitches), so never let a duplicate into the list.
            val posts = repository.posts(cid).distinctBy { it.postId }
            if (token != loadGeneration) return
            _state.update { it.copy(posts = posts, hasMorePosts = posts.isNotEmpty()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (token == loadGeneration) _state.update { it.copy(errorMessage = e.userMessage()) }
        } finally {
            if (token == loadGeneration) _state.update { it.copy(isLoadingPosts = false) }
        }
    }

    /** A tile appeared: when it's the last one, fetch the next page. */
    fun loadMoreIfNeeded(currentPost: CommunityPostCard) {
        val s = _state.value
        if (!s.hasMorePosts || s.isLoadingMore || currentPost.postId != s.posts.lastOrNull()?.postId) return
        loadMore()
    }

    private fun loadMore() {
        val lastId = _state.value.posts.lastOrNull()?.postId ?: return
        val token = loadGeneration
        // Set before launching so a second "last tile appeared" can't start a duplicate page.
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            try {
                val newPosts = repository.posts(cid, before = lastId)
                if (token != loadGeneration) return@launch
                _state.update { s ->
                    // Android-only guards (iOS appends blindly; its ForEach tolerates repeated ids,
                    // but a repeated LazyVerticalGrid key crashes):
                    // - the list was replaced (a reload that started before this page and landed
                    //   first shares our token) → this page continues a list that's gone; drop it.
                    // - the server ignores `before` when that post no longer exists and sends page 1
                    //   again → drop already-shown posts, and stop paging when nothing new came back
                    //   instead of looping on page 1.
                    if (s.posts.lastOrNull()?.postId != lastId) {
                        s
                    } else {
                        val known = s.posts.mapTo(HashSet()) { it.postId }
                        val fresh = newPosts.filter { it.postId !in known }.distinctBy { it.postId }
                        s.copy(posts = s.posts + fresh, hasMorePosts = fresh.isNotEmpty())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silent — a pagination hiccup shouldn't interrupt browsing.
            } finally {
                _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun toggleJoin() {
        val current = _state.value
        if (current.isJoining) return
        val wasJoined = current.visitorCommunity?.joined == true
        _state.update { it.copy(isJoining = true) }
        viewModelScope.launch {
            try {
                if (wasJoined) {
                    repository.leave(cid)
                    _state.update { s ->
                        s.copy(
                            visitorCommunity = s.visitorCommunity?.let { c ->
                                c.copy(joined = false, memberCount = maxOf(0, (c.memberCount ?: 1) - 1))
                            },
                        )
                    }
                } else {
                    repository.join(cid)
                    _state.update { s ->
                        s.copy(
                            visitorCommunity = s.visitorCommunity?.let { c ->
                                c.copy(joined = true, memberCount = (c.memberCount ?: 0) + 1)
                            },
                        )
                    }
                }
                joinGeneration += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            } finally {
                _state.update { it.copy(isJoining = false) }
            }
        }
    }

    fun selectPost(post: CommunityPostCard) {
        _state.update { it.copy(selectedPost = post) }
    }

    fun dismissPost() {
        _state.update { it.copy(selectedPost = null) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /** Applies [transform] to the post in the grid and, if it's the one open, in the sheet too. */
    private fun updatePost(postId: String, transform: (CommunityPostCard) -> CommunityPostCard) {
        _state.update { s ->
            s.copy(
                posts = s.posts.map { if (it.postId == postId) transform(it) else it },
                selectedPost = s.selectedPost?.let { if (it.postId == postId) transform(it) else it },
            )
        }
    }

    fun vote(postId: String, optionId: String) {
        viewModelScope.launch {
            try {
                val result = repository.vote(postId, optionId)
                updatePost(postId) { it.copy(poll = result.poll, myVote = result.myVote) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            }
        }
    }

    fun rsvp(postId: String, going: Boolean) {
        viewModelScope.launch {
            try {
                val result = repository.rsvp(postId, going)
                updatePost(postId) { it.copy(attendeeCount = result.attendeeCount, myRsvp = result.going) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            }
        }
    }

    /** Owner only: flip `active`, close the sheet, reload (the grid re-sorts/re-badges). */
    fun togglePublish(post: CommunityPostCard) {
        viewModelScope.launch {
            try {
                repository.setPostActive(post.postId, active = !(post.active ?: true))
                _state.update { it.copy(selectedPost = null) }
                performLoad()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            }
        }
    }

    fun report(reason: String) {
        viewModelScope.launch {
            try {
                repository.report(cid, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            }
        }
    }

    fun block() {
        val name = _state.value.visitorCommunity?.name
        viewModelScope.launch {
            try {
                repository.block(cid, name)
                _events.send(CommunityPageEvent.Dismiss)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            }
        }
    }
}
