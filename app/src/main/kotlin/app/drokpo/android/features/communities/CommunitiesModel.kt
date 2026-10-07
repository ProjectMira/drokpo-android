package app.drokpo.android.features.communities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.ContentEvents
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.FeedItem
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many directory entries the empty-rail "Communities to discover" strip asks for. */
internal const val DISCOVER_LIMIT = 20

/** State of the person-side Communities home (iOS CommunitiesView's `@State`). */
internal data class CommunitiesUiState(
    /** Joined communities — the avatar rail. */
    val mine: List<CommunityProfile> = emptyList(),
    /** Posts from joined communities with sponsored cards interleaved (server order). */
    val items: List<FeedItem> = emptyList(),
    /**
     * Communities to suggest (any registration state — verification is a
     * badge, not a visibility gate) — only fetched while [mine] is empty.
     */
    val discover: List<CommunityProfile> = emptyList(),
    val isLoading: Boolean = true,
    /** Pull-to-refresh indicator (iOS `.refreshable` keeps its spinner up until load() returns). */
    val isRefreshing: Boolean = false,
    /** A load has succeeded at least once (drives the reload-on-reappearance rule). */
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
) {
    /**
     * The "Communities to discover" section replaces the rail while nothing is
     * joined. iOS hides it during *every* load (`mine.isEmpty && !isLoading`),
     * which made it blink out on each reappearance reload; once something has
     * loaded it stays up while a reload runs.
     */
    val showsDiscover: Boolean get() = mine.isEmpty() && (!isLoading || hasLoaded)

    /** The rail of joined communities (iOS shows it whenever the discover section isn't shown). */
    val showsRail: Boolean get() = mine.isNotEmpty()

    /** "From your communities" header — only when something is joined. */
    val showsFeedHeader: Boolean get() = mine.isNotEmpty()

    /** "Posts from your communities will show up here." (same no-blink rule as [showsDiscover]). */
    val showsFeedEmpty: Boolean get() = items.isEmpty() && mine.isNotEmpty() && (!isLoading || hasLoaded)

    /** Centre spinner: only while the first load runs with nothing to show. */
    val showsSpinner: Boolean get() = isLoading && !hasLoaded && mine.isEmpty() && items.isEmpty()

    /**
     * The communities feed never serves person or news items; they're skipped
     * defensively. Lazy-list keys must be unique, so a repeated item shows once.
     */
    val feed: List<FeedItem>
        get() = items.filter { it is FeedItem.Post || it is FeedItem.Ad }.distinctBy { it.id }
}

/**
 * Person accounts' community browsing, YouTube-style: the communities you've
 * joined as an avatar rail up top, and below it a single feed of their posts
 * with sponsored cards mixed in (GET /api/communities/home). Port of the data
 * half of iOS CommunitiesView.
 */
internal class CommunitiesModel(
    private val api: CommunitiesApi = RemoteCommunitiesApi,
    /** Fire-and-forget `POST /api/{path}/events {"event":"click"}`. */
    private val reportClick: (path: String) -> Unit = ContentEvents::click,
) : ViewModel() {
    private val _state = MutableStateFlow(CommunitiesUiState())
    val state: StateFlow<CommunitiesUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0

    init {
        // iOS `.task { await load() }`.
        load(refreshing = false)
    }

    /**
     * iOS `.onAppear { if hasLoaded { load() } }` — re-fires when popping back
     * from a community page (unlike `.task`), so a join/leave there is
     * reflected here immediately.
     */
    fun onAppear() {
        if (_state.value.hasLoaded) load(refreshing = false)
    }

    /** Pull-to-refresh. */
    fun refresh() = load(refreshing = true)

    private fun load(refreshing: Boolean) {
        // One load at a time: a newer request supersedes an older one, so a
        // slow response can't overwrite fresher data (or clear isLoading early).
        loadJob?.cancel()
        val generation = ++loadGeneration
        _state.update { it.copy(isLoading = true, isRefreshing = refreshing) }
        loadJob = viewModelScope.launch {
            try {
                val home = api.home()
                val mine = home.communities.orEmpty()
                val items = home.items.orEmpty()
                if (mine.isEmpty()) {
                    // Fetch the suggestions *before* publishing an empty `mine`:
                    // the discover section stays up during reloads (no blink), so
                    // publishing first would flash "No communities to discover
                    // yet." (e.g. after leaving the last joined community) while
                    // the directory is still on its way. iOS hides it mid-load.
                    val discover = try {
                        api.directory(DISCOVER_LIMIT).communities.orEmpty()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // iOS has already applied the home response by now; keep it.
                        _state.update { it.copy(mine = mine, items = items) }
                        throw e
                    }
                    _state.update { it.copy(mine = mine, items = items, discover = discover, hasLoaded = true) }
                } else {
                    _state.update { it.copy(mine = mine, items = items, hasLoaded = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            } finally {
                if (generation == loadGeneration) {
                    _state.update { it.copy(isLoading = false, isRefreshing = false) }
                }
            }
        }
    }

    /**
     * Like the iOS `Task {}` behind the button, a vote finishes even if the
     * cover is closed meanwhile — the tap is never silently dropped.
     */
    fun vote(post: CommunityPostCard, optionId: String) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    val result = api.vote(post.postId, optionId)
                    updatePost(post.postId) { it.copy(poll = result.poll, myVote = result.myVote) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(errorMessage = e.userMessage()) }
                }
            }
        }
    }

    /** Finishes even if the cover is closed meanwhile (see [vote]). */
    fun rsvp(post: CommunityPostCard, going: Boolean) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    val result = api.rsvp(post.postId, going)
                    updatePost(post.postId) { it.copy(attendeeCount = result.attendeeCount, myRsvp = result.going) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(errorMessage = e.userMessage()) }
                }
            }
        }
    }

    /** A post or ad link was opened in-app: record the click ("posts/{id}" | "ads/{id}"). */
    fun linkOpened(clickPath: String) = reportClick(clickPath)

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    /** Replaces one post in place (iOS `updatePost(_:_:)`); a post no longer listed is ignored. */
    private fun updatePost(postId: String, mutate: (CommunityPostCard) -> CommunityPostCard) {
        _state.update { state -> state.copy(items = state.items.updatingPost(postId, mutate)) }
    }
}

/** `items` with the post `postId` replaced by `mutate(post)`; unchanged when it isn't there. */
internal fun List<FeedItem>.updatingPost(
    postId: String,
    mutate: (CommunityPostCard) -> CommunityPostCard,
): List<FeedItem> {
    val index = indexOfFirst { it.id == "post-$postId" }
    val item = getOrNull(index) as? FeedItem.Post ?: return this
    return toMutableList().also { it[index] = FeedItem.Post(mutate(item.post)) }
}
