package app.drokpo.android.features.likes

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.LikedContent
import app.drokpo.android.core.model.LikedContentResponse
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.SwipeAction
import app.drokpo.android.core.model.SwipeEntry
import app.drokpo.android.core.model.SwipeIn
import app.drokpo.android.core.model.SwipeResult
import app.drokpo.android.core.model.TolerantList
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** "You liked" sits left and is the default; "Liked you" moved right. */
internal enum class Direction(val label: String) {
    Given("You liked"),
    Received("Liked you"),
}

/**
 * Pill filters for "You liked": everything you saved, ordered newest
 * first, or narrowed to people, community posts, or news stories.
 */
internal enum class GivenFilter(val label: String) {
    All("All"),
    Friends("Friends"),
    Communities("Communities"),
    News("News"),
}

/** One row of the merged "You liked" list. */
@Immutable
internal sealed interface GivenEntry {
    val id: String

    /** ISO timestamps sort correctly as strings; missing ones sink to the bottom. */
    val sortKey: String

    data class Person(val entry: SwipeEntry) : GivenEntry {
        override val id: String get() = "person-${entry.id}"
        override val sortKey: String get() = entry.createdAt ?: ""
    }

    data class Content(val content: LikedContent) : GivenEntry {
        override val id: String get() = content.id
        override val sortKey: String get() = content.likedAt ?: ""
    }
}

/**
 * The "You liked" list for [filter]: people you liked ([given], by `createdAt`) merged with
 * the news and community posts you saved ([likedContent], by `likedAt`), newest first.
 * "Communities" means saved community *posts*; a community account you liked as a
 * counterpart is one of the people, so it shows under "Friends".
 */
internal fun givenEntries(
    given: List<SwipeEntry>,
    likedContent: List<LikedContent>,
    filter: GivenFilter,
): List<GivenEntry> {
    val entries = mutableListOf<GivenEntry>()
    if (filter == GivenFilter.All || filter == GivenFilter.Friends) {
        entries += given.map { GivenEntry.Person(it) }
    }
    if (filter == GivenFilter.All || filter == GivenFilter.Communities) {
        entries += likedContent.filterIsInstance<LikedContent.Post>().map { GivenEntry.Content(it) }
    }
    if (filter == GivenFilter.All || filter == GivenFilter.News) {
        entries += likedContent.filterIsInstance<LikedContent.News>().map { GivenEntry.Content(it) }
    }
    // distinctBy: LazyColumn throws on a repeated key, where SwiftUI's List merely tolerates a
    // duplicated id — keep the newest copy if the backend ever returns one twice.
    return entries.sortedByDescending { it.sortKey }.distinctBy { it.id }
}

/** One "Liked you" row: an entry that carries the liker's card. */
@Immutable
internal data class ReceivedRow(val id: String, val card: FeedCard)

/**
 * iOS renders a row only `if let card = entry.otherUser`; ids are made unique because
 * LazyColumn throws on a repeated key (SwiftUI's List tolerates one).
 */
internal fun receivedRows(received: List<SwipeEntry>): List<ReceivedRow> =
    received.mapNotNull { entry -> entry.otherUser?.let { ReceivedRow(entry.id, it) } }.distinctBy { it.id }

/** The row-heart match alert: "You and {name} liked each other." */
@Immutable
internal data class MatchedAlert(val name: String, val matchId: String?)

@Immutable
internal data class LikesUiState(
    val direction: Direction = Direction.Given,
    val givenFilter: GivenFilter = GivenFilter.All,
    val received: List<SwipeEntry> = emptyList(),
    val given: List<SwipeEntry> = emptyList(),
    val likedContent: List<LikedContent> = emptyList(),
    /** Full-screen spinner — only while all three lists are empty (see [LikesModel.load]). */
    val isLoading: Boolean = true,
    /** A pull-to-refresh load is running (drives the refresh indicator only). */
    val isRefreshing: Boolean = false,
    val matched: MatchedAlert? = null,
    val errorMessage: String? = null,
) {
    val givenEntries: List<GivenEntry> = givenEntries(given, likedContent, givenFilter)
    val receivedRows: List<ReceivedRow> = receivedRows(received)
}

/** The Likes endpoints, behind an interface so JVM tests can swap in a fake. */
internal interface LikesRepository {
    /** GET /api/swipes/received?action=like */
    suspend fun receivedLikes(): List<SwipeEntry>

    /** GET /api/swipes?action=like */
    suspend fun givenLikes(): List<SwipeEntry>

    /** GET /api/likes/content */
    suspend fun likedContent(): List<LikedContent>

    /** POST /api/swipes/{uid} {"action": "like"} */
    suspend fun likeBack(uid: String): SwipeResult

    /** DELETE /api/news/{newsId}/like */
    suspend fun unlikeNews(newsId: String)

    /** DELETE /api/posts/{postId}/like */
    suspend fun unlikePost(postId: String)
}

/** The real endpoints. [api] is the shared client; tests point one at a MockWebServer. */
internal class RemoteLikesRepository(private val api: ApiClient = ApiClient.shared) : LikesRepository {
    override suspend fun receivedLikes(): List<SwipeEntry> =
        api.get<TolerantList<SwipeEntry>>("/api/swipes/received", listOf("action" to "like")).items

    override suspend fun givenLikes(): List<SwipeEntry> =
        api.get<TolerantList<SwipeEntry>>("/api/swipes", listOf("action" to "like")).items

    /** iOS `(try await contentList.items) ?? []`: a missing or null `items` is no saved content. */
    override suspend fun likedContent(): List<LikedContent> =
        api.get<LikedContentResponse>("/api/likes/content").items.orEmpty()

    override suspend fun likeBack(uid: String): SwipeResult =
        api.post<SwipeResult>("/api/swipes/$uid", SwipeIn(action = SwipeAction.like))

    override suspend fun unlikeNews(newsId: String) {
        api.delete<EmptyResponse>("/api/news/$newsId/like")
    }

    override suspend fun unlikePost(postId: String) {
        api.delete<EmptyResponse>("/api/posts/$postId/like")
    }
}

/**
 * Port of LikesView's state and actions. Owned by LikesScreen (the tab root, session scope —
 * CONTRACT §D.3), so the pushed "Liked you" profile shares it for its "Like back" button and
 * the segment/filter survive tab switches, like iOS `@State` in a TabView child.
 */
internal class LikesModel(
    private val repository: LikesRepository = RemoteLikesRepository(),
    /** DeepLinkRouter.focusLikedYou — set by MainTabs for a "like" push, consumed here. */
    private val focusLikedYou: MutableStateFlow<Boolean> = AppGraph.deepLinks.focusLikedYou,
    /** "Say hi": DeepLinkRouter.handle("message", matchId) → MainTabs selects Chats → thread. */
    private val openThread: (matchId: String) -> Unit = { AppGraph.deepLinks.handle("message", it) },
) : ViewModel() {
    private val _state = MutableStateFlow(LikesUiState())
    val state: StateFlow<LikesUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** Row hearts mid-request, by uid: a second tap on the same heart is ignored. */
    private val likingUids = mutableSetOf<String>()

    /** DELETE …/like requests in flight, by [LikedContent.id] (a repeat swipe joins the first). */
    private val removals = mutableMapOf<String, Deferred<Boolean>>()

    /** iOS `.onAppear`: every time the Likes root shows (tab select, pop back). */
    fun onAppear() {
        consumeLikePush()
        load()
    }

    /**
     * A "like" push should land on the "Liked you" segment (the default is
     * "You liked") — MainTabs flags it on the router, consumed here.
     */
    fun consumeLikePush() {
        if (!focusLikedYou.value) return
        focusLikedYou.value = false
        _state.update { it.copy(direction = Direction.Received) }
    }

    fun selectDirection(direction: Direction) {
        _state.update { it.copy(direction = direction) }
    }

    fun selectFilter(filter: GivenFilter) {
        _state.update { it.copy(givenFilter = filter) }
    }

    /** Pull-to-refresh (`.refreshable`): the same load, with the refresh indicator up. */
    fun refresh(): Job = load(userInitiated = true)

    /**
     * Only shows the full-screen spinner on the very first load; later calls
     * (tab reselected, pull-to-refresh, returning from a like push) refresh
     * silently so the existing list doesn't flash.
     *
     * Matched people are dropped from both lists — they already show up in
     * Chats (New matches / conversations), so keeping them here duplicated
     * the same person under both "Liked you" and "You liked".
     *
     * The three requests run in parallel and are applied in order, exactly like the iOS
     * `async let`s: a failure keeps whatever was already applied, skips the rest and shows the
     * error. A newer load cancels an older one still in flight, so a slow response can't
     * overwrite fresher data.
     */
    fun load(userInitiated: Boolean = false): Job {
        loadJob?.cancel()
        val job = viewModelScope.launch {
            _state.update { s ->
                val empty = s.received.isEmpty() && s.given.isEmpty() && s.likedContent.isEmpty()
                s.copy(
                    isLoading = if (empty) true else s.isLoading,
                    isRefreshing = if (userInitiated) true else s.isRefreshing,
                )
            }
            try {
                supervisorScope {
                    val received = async { repository.receivedLikes() }
                    val given = async { repository.givenLikes() }
                    val content = async { repository.likedContent() }
                    val receivedItems = received.await().filter { !it.isMatched }
                    _state.update { it.copy(received = receivedItems) }
                    val givenItems = given.await().filter { !it.isMatched }
                    _state.update { it.copy(given = givenItems) }
                    val contentItems = content.await()
                    _state.update { it.copy(likedContent = contentItems) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                _state.update { it.copy(errorMessage = e.userMessage()) }
            }
            _state.update { it.copy(isLoading = false, isRefreshing = false) }
        }
        loadJob = job
        return job
    }

    /**
     * Shared by both the row's quick-like heart and the pushed
     * ProfileDetailScreen's "Like back" button. Only removes the row from
     * `received` — it does NOT set `matched`, because ProfileDetailScreen
     * shows its own match alert and setting `matched` here too would
     * present two alerts for the same tap.
     *
     * Runs in viewModelScope, so the like still lands (and the row still goes) if the profile
     * screen that asked is popped mid-request.
     */
    suspend fun likeBack(card: FeedCard): SwipeResult? = viewModelScope.async {
        try {
            val result = repository.likeBack(card.uid)
            _state.update { s -> s.copy(received = s.received.filterNot { it.otherUser?.uid == card.uid }) }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = e.userMessage()) }
            null
        }
    }.await()

    /** The row heart: like back, and if that made a match, "It's a match!". */
    fun likeBackFromRow(card: FeedCard) {
        if (!likingUids.add(card.uid)) return
        viewModelScope.launch {
            try {
                val result = likeBack(card)
                if (result != null && result.isMatch) {
                    _state.update {
                        it.copy(
                            matched = MatchedAlert(
                                name = card.displayName ?: "they",
                                matchId = result.matchId ?: result.match?.matchId,
                            ),
                        )
                    }
                }
            } finally {
                likingUids.remove(card.uid)
            }
        }
    }

    /**
     * "Remove" on a saved news row: DELETE, then drop it locally — not optimistic, exactly as on
     * iOS; a failure keeps the row and shows the error. Returns whether it was removed, so a
     * swiped-away row knows to slide back.
     */
    suspend fun unlikeNews(item: NewsCard): Boolean =
        removeSaved(LikedContent.News(item, likedAt = null).id) { repository.unlikeNews(item.newsId) }

    /** "Remove" on a saved post row (see [unlikeNews]). */
    suspend fun unlikePost(post: CommunityPostCard): Boolean =
        removeSaved(LikedContent.Post(post, likedAt = null).id) { repository.unlikePost(post.postId) }

    /**
     * Runs in viewModelScope and is only awaited by the caller: the removal still applies if
     * the row (or the whole tab) leaves composition mid-request. LAZY so the map entry exists
     * before the body can run (and finish) on Main.immediate.
     */
    private suspend fun removeSaved(id: String, request: suspend () -> Unit): Boolean {
        removals[id]?.let { return it.await() }
        val removal = viewModelScope.async(start = CoroutineStart.LAZY) {
            try {
                request()
                _state.update { s -> s.copy(likedContent = s.likedContent.filterNot { it.id == id }) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
                false
            }
        }
        removals[id] = removal
        removal.invokeOnCompletion { if (removals[id] === removal) removals.remove(id) }
        return removal.await()
    }

    /** "Say hi" on the match alert (the alert has already cleared [LikesUiState.matched]). */
    fun sayHi(matchId: String?) {
        if (matchId != null) openThread(matchId)
    }

    fun dismissMatch() {
        _state.update { it.copy(matched = null) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
