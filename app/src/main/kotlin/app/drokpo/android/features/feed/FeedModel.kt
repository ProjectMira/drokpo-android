package app.drokpo.android.features.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.ContentEvents
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.Safety
import app.drokpo.android.core.model.AdCard
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.FeedItem
import app.drokpo.android.core.model.FeedPage
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.RsvpResult
import app.drokpo.android.core.model.SwipeAction
import app.drokpo.android.core.model.SwipeIn
import app.drokpo.android.core.model.SwipeResult
import app.drokpo.android.core.model.VoteIn
import app.drokpo.android.core.model.VoteResult
import app.drokpo.android.core.tryOrNull
import app.drokpo.android.core.userMessage
import app.drokpo.android.features.shared.sharing.ShareableContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One card in the Discover deck: a real member, or one of the three content
 * queues (ads, news, community posts) mixed in between them.
 */
internal sealed interface DeckItem {
    val id: String

    val profileCard: FeedCard? get() = (this as? Profile)?.card

    data class Profile(val card: FeedCard) : DeckItem {
        override val id: String get() = "profile-${card.uid}"
    }

    data class Ad(val ad: AdCard) : DeckItem {
        override val id: String get() = "ad-${ad.adId}"
    }

    data class News(val item: NewsCard) : DeckItem {
        override val id: String get() = "news-${item.newsId}"
    }

    data class Post(val post: CommunityPostCard) : DeckItem {
        override val id: String get() = "post-${post.postId}"
    }
}

/** The stamp a right swipe shows: profiles "LIKE", ads "VISIT", news/posts "SAVE", events "JOIN". */
internal val DeckItem.likeLabel: String
    get() = when (this) {
        is DeckItem.Profile -> "LIKE"
        is DeckItem.Ad -> "VISIT"
        is DeckItem.News -> "SAVE"
        is DeckItem.Post -> if (post.kind == "event") "JOIN" else "SAVE"
    }

/**
 * What the deck's share button would share — the card mapped to a shareable
 * payload. Ads aren't shareable (null disables the button); a community
 * account's person-shaped card shares as the community.
 */
internal val DeckItem.shareContent: ShareableContent?
    get() = when (this) {
        is DeckItem.Profile ->
            if (card.isCommunity) ShareableContent.Community(cid = card.uid, name = card.displayName) else ShareableContent.Profile(card)
        is DeckItem.News -> ShareableContent.News(item)
        is DeckItem.Post -> ShareableContent.Post(post)
        is DeckItem.Ad -> null
    }

/** `URL(string:)` for the links the deck opens: null when missing or blank. */
internal fun String?.linkOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

internal val AdCard.link: String? get() = linkUrl.linkOrNull()
internal val NewsCard.link: String? get() = sourceUrl.linkOrNull()
internal val CommunityPostCard.link: String? get() = linkUrl.linkOrNull()

/** Everything FeedScreen renders from [FeedModel]. */
internal data class FeedState(
    val deck: List<DeckItem> = emptyList(),
    /** Only the initial load shows the spinner (loadInitial runs only while the deck is empty). */
    val isLoading: Boolean = false,
    val matchedCard: FeedCard? = null,
    val errorMessage: String? = null,
    /**
     * Link to open in the in-app browser — set by swiping right on an ad, or
     * by a news card's arrow / a link post's CTA. One-shot: the screen opens it
     * and calls [FeedModel.consumeUrlToOpen].
     */
    val urlToOpen: String? = null,
    /**
     * The last profile swiped this session, undoable until the next swipe.
     * Cleared on match (undo is refused server-side once matched).
     */
    val lastSwipedProfile: FeedCard? = null,
    /**
     * The post whose detail sheet is open. Lives here (not in the screen) so a
     * vote/RSVP that finishes after the tap updates both the deck entry and
     * the open sheet.
     */
    val expandedPost: CommunityPostCard? = null,
) {
    val canUndo: Boolean get() = lastSwipedProfile != null
}

/** The Discover deck's REST calls (iOS FeedModel's `APIClient.shared` uses), injectable for tests. */
internal interface FeedApi {
    /** GET /api/feed?limit=20&shape=items */
    suspend fun fetchPage(): FeedPage

    /** POST /api/swipes/{uid} {action} */
    suspend fun swipe(uid: String, action: SwipeAction): SwipeResult

    /** DELETE /api/swipes/{uid} */
    suspend fun undoSwipe(uid: String)

    /** PUT /api/news/{newsId}/like */
    suspend fun saveNews(newsId: String)

    /** PUT /api/posts/{postId}/like */
    suspend fun savePost(postId: String)

    /** POST /api/posts/{postId}/vote {optionId} */
    suspend fun vote(postId: String, optionId: String): VoteResult

    /** POST (going) / DELETE (not going) /api/posts/{postId}/rsvp */
    suspend fun rsvp(postId: String, going: Boolean): RsvpResult

    /** POST /api/reports {reportedUid, reason, note: ""} */
    suspend fun report(uid: String, reason: String)

    /** POST /api/blocks/{uid}, then remember it in BlockStore. */
    suspend fun block(uid: String, displayName: String?)
}

internal class RemoteFeedApi(private val client: ApiClient = ApiClient.shared) : FeedApi {
    override suspend fun fetchPage(): FeedPage =
        client.get<FeedPage>("/api/feed", listOf("limit" to "20", "shape" to "items"))

    override suspend fun swipe(uid: String, action: SwipeAction): SwipeResult =
        client.post<SwipeResult>("/api/swipes/$uid", SwipeIn(action = action))

    override suspend fun undoSwipe(uid: String) {
        client.delete<EmptyResponse>("/api/swipes/$uid")
    }

    // iOS decodes the returned card but ignores it (`try?`); any JSON object will do.
    override suspend fun saveNews(newsId: String) {
        client.put<EmptyResponse>("/api/news/$newsId/like")
    }

    override suspend fun savePost(postId: String) {
        client.put<EmptyResponse>("/api/posts/$postId/like")
    }

    override suspend fun vote(postId: String, optionId: String): VoteResult =
        client.post<VoteResult>("/api/posts/$postId/vote", VoteIn(optionId = optionId))

    override suspend fun rsvp(postId: String, going: Boolean): RsvpResult =
        if (going) client.post<RsvpResult>("/api/posts/$postId/rsvp") else client.delete<RsvpResult>("/api/posts/$postId/rsvp")

    override suspend fun report(uid: String, reason: String) = Safety.report(reportedUid = uid, reason = reason)

    override suspend fun block(uid: String, displayName: String?) = Safety.block(uid = uid, displayName = displayName)
}

/**
 * Port of iOS FeedModel: the Discover deck — server-ordered typed pages (or
 * legacy client-side mixing), session dedup, optimistic swipes with undo,
 * content-card saves/opens and their analytics.
 *
 * Created in the session scope (CONTRACT §D.3), so the deck survives tab
 * switches and configuration changes and is dropped on sign-out.
 */
internal class FeedModel(
    private val api: FeedApi = RemoteFeedApi(),
    /** Fire-and-forget POST /api/{path}/events — never surfaces errors. */
    private val sendContentEvent: (path: String, event: String) -> Unit = ContentEvents::send,
) : ViewModel() {
    companion object {
        /** How many real profiles appear between content cards (legacy client-side mixing). */
        const val PROFILES_PER_AD = 3

        /** Refill once the deck is down to this many cards. */
        const val REFILL_THRESHOLD = 3
    }

    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private val deck: List<DeckItem> get() = _state.value.deck

    private var isFetching = false
    private var loadJob: Job? = null

    /**
     * Everyone swiped this session. The backend also filters swiped users,
     * but a feed fetch that races an in-flight swipe POST can still return
     * someone we just swiped — never re-add them.
     */
    private val swipedUids = mutableSetOf<String>()

    /**
     * Content cards saved (liked) this session — don't re-serve them when
     * the backend cycles the same news/ads again to keep the deck full.
     */
    private val likedContentIds = mutableSetOf<String>()

    /** Content cards (any type) whose impression we already reported this session. */
    private val impressedContentIds = mutableSetOf<String>()

    // Active content from the last legacy feed response, each inserted
    // round-robin and cycling ad -> news -> post as the deck refills.
    private val adQueue = ContentQueue<AdCard> { it.adId }
    private val newsQueue = ContentQueue<NewsCard> { it.newsId }
    private val postQueue = ContentQueue<CommunityPostCard> { it.postId }

    /** Which content queue serves next. */
    private var contentTypeCursor = 0

    /** Profiles appended since a content card was last inserted. */
    private var profilesSinceContent = 0

    // region Loading

    /** `.task { await model.loadInitial() }` — spinner only while the deck is empty. */
    fun loadInitial() {
        if (deck.isNotEmpty() || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                fetchMore()
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /** The empty state's "Refresh" (no spinner, like iOS). */
    fun refresh() {
        viewModelScope.launch { fetchMore() }
    }

    internal suspend fun fetchMore() {
        if (isFetching) return
        isFetching = true
        try {
            val page = api.fetchPage()
            val items = page.items
            if (items != null) {
                appendServerOrdered(items)
            } else {
                // Older backend without ?shape=items — legacy client mixing.
                adQueue.items = page.ads.orEmpty()
                newsQueue.items = page.news.orEmpty()
                postQueue.items = page.communityPosts.orEmpty()
                val known = deck.mapNotNullTo(HashSet()) { it.profileCard?.uid }
                val fresh = page.candidates.orEmpty().filter { it.uid !in known && it.uid !in swipedUids }
                appendInterleaving(fresh)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e)
        } finally {
            isFetching = false
        }
    }

    /**
     * Appends a server-ordered page, dropping anything already in the deck,
     * already swiped, or already saved this session. The server keeps every
     * page topped up with content, so re-fetching once the deck runs low
     * cycles news/ads on repeat instead of going empty.
     */
    private fun appendServerOrdered(items: List<FeedItem>) {
        updateDeck { current ->
            val deckIds = current.mapTo(HashSet()) { it.id }
            val appended = current.toMutableList()
            for (item in items) {
                val deckItem = when (item) {
                    is FeedItem.Person -> {
                        if (item.card.uid in swipedUids) continue
                        DeckItem.Profile(item.card)
                    }
                    is FeedItem.Ad -> DeckItem.Ad(item.ad)
                    is FeedItem.News -> DeckItem.News(item.item)
                    is FeedItem.Post -> DeckItem.Post(item.post)
                }
                if (deckItem.id in deckIds || deckItem.id in likedContentIds) continue
                deckIds += deckItem.id
                appended += deckItem
            }
            appended
        }
    }

    /**
     * Appends profiles to the deck, inserting one content card after every
     * [PROFILES_PER_AD] real profiles (when one of the three queues has
     * something new to show).
     */
    private fun appendInterleaving(profiles: List<FeedCard>) {
        val working = deck.toMutableList()
        for (card in profiles) {
            working += DeckItem.Profile(card)
            profilesSinceContent += 1
            if (profilesSinceContent >= PROFILES_PER_AD) {
                val item = nextContentItem(working)
                if (item != null) {
                    working += item
                    profilesSinceContent = 0
                }
            }
        }
        updateDeck { working }
    }

    /**
     * Cycles ad -> news -> post, skipping any queue that's empty or whose
     * items are all already in the deck; null once all three come up empty.
     */
    private fun nextContentItem(current: List<DeckItem>): DeckItem? {
        val adIds = current.mapNotNullTo(HashSet()) { (it as? DeckItem.Ad)?.ad?.adId }
        val newsIds = current.mapNotNullTo(HashSet()) { (it as? DeckItem.News)?.item?.newsId }
        val postIds = current.mapNotNullTo(HashSet()) { (it as? DeckItem.Post)?.post?.postId }
        repeat(3) {
            val item = when (contentTypeCursor % 3) {
                0 -> adQueue.next(adIds)?.let(DeckItem::Ad)
                1 -> newsQueue.next(newsIds)?.let(DeckItem::News)
                else -> postQueue.next(postIds)?.let(DeckItem::Post)
            }
            contentTypeCursor += 1
            if (item != null) return item
        }
        return null
    }

    // endregion

    // region Swipes

    /** Pass/like routed to whatever kind of card [item] is (deck drag, buttons, CTA). */
    fun swipe(item: DeckItem, liked: Boolean) {
        when (item) {
            is DeckItem.Profile -> swipe(item.card, if (liked) SwipeAction.like else SwipeAction.pass)
            is DeckItem.Ad -> swipeAd(item.ad, liked)
            is DeckItem.News -> swipeNews(item.item, liked)
            is DeckItem.Post -> swipePost(item.post, liked)
        }
    }

    /** Route the pass/like buttons to whatever sits on top of the deck. */
    fun swipeTop(liked: Boolean) {
        deck.firstOrNull()?.let { swipe(it, liked) }
    }

    /** Removes the card immediately for a snappy deck, then records the swipe. */
    fun swipe(card: FeedCard, action: SwipeAction) {
        updateDeck { current -> current.filterNot { it.profileCard?.uid == card.uid } }
        swipedUids += card.uid
        _state.update { it.copy(lastSwipedProfile = card) }
        viewModelScope.launch {
            try {
                val result = api.swipe(card.uid, action)
                if (action != SwipeAction.pass && result.isMatch) {
                    _state.update {
                        it.copy(
                            matchedCard = card,
                            // A match can't be undone (the server refuses), so drop
                            // the undo affordance rather than offer a dead button.
                            lastSwipedProfile = if (it.lastSwipedProfile?.uid == card.uid) null else it.lastSwipedProfile,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            }
            if (deck.size <= REFILL_THRESHOLD) fetchMore()
        }
    }

    /** Rewind: forget the last swipe on the server and put the card back on top. */
    fun undoLastSwipe() {
        val card = _state.value.lastSwipedProfile ?: return
        _state.update { it.copy(lastSwipedProfile = null) }
        viewModelScope.launch {
            try {
                api.undoSwipe(card.uid)
                swipedUids -= card.uid
                updateDeck { current -> listOf(DeckItem.Profile(card)) + current.filterNot { it.profileCard?.uid == card.uid } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    /**
     * A sponsored card was swiped. Liking opens the link in the in-app
     * browser; either way the card leaves the deck and no swipe is recorded.
     */
    fun swipeAd(ad: AdCard, liked: Boolean) {
        updateDeck { current -> current.filterNot { it.id == "ad-${ad.adId}" } }
        val url = ad.link
        if (liked && url != null) {
            _state.update { it.copy(urlToOpen = url) }
            sendContentEvent("ads/${ad.adId}", "click")
        }
        refillIfLow()
    }

    /**
     * A news card was swiped. Liking SAVES the story to the Likes tab (the
     * source opens via the card's arrow button or the detail sheet, never
     * the swipe itself); no swipe is recorded either way.
     */
    fun swipeNews(item: NewsCard, liked: Boolean) {
        updateDeck { current -> current.filterNot { it.id == "news-${item.newsId}" } }
        if (liked) {
            likedContentIds += "news-${item.newsId}"
            viewModelScope.launch {
                // Fire-and-forget save; failures must not interrupt swiping.
                tryOrNull { api.saveNews(item.newsId) }
            }
        }
        refillIfLow()
    }

    /**
     * Opens a news card's source article in the in-app browser (arrow
     * button), counting the click. The card stays in the deck.
     */
    fun openNews(item: NewsCard) {
        val url = item.link ?: return
        _state.update { it.copy(urlToOpen = url) }
        sendContentEvent("news/${item.newsId}", "click")
    }

    /** Records the click when the news detail sheet opens the source ("Read the full story"). */
    fun reportNewsClick(item: NewsCard) {
        sendContentEvent("news/${item.newsId}", "click")
    }

    fun reportPostClick(post: CommunityPostCard) {
        sendContentEvent("posts/${post.postId}", "click")
    }

    /**
     * A community post was swiped. Liking SAVES the post to the Likes tab,
     * and for an event additionally RSVPs (the gesture reads as "I'm
     * coming"). Links open via the card's CTA, not the swipe. No swipe is
     * recorded and the card leaves the deck immediately.
     */
    fun swipePost(post: CommunityPostCard, liked: Boolean) {
        updateDeck { current -> current.filterNot { it.id == "post-${post.postId}" } }
        if (liked) {
            likedContentIds += "post-${post.postId}"
            viewModelScope.launch { tryOrNull { api.savePost(post.postId) } }
            if (post.kind == "event") {
                viewModelScope.launch { rsvpNow(post, going = true) }
            }
        }
        refillIfLow()
    }

    /**
     * Opens a link post's URL in the in-app browser (CTA button), counting
     * the click. The card stays in the deck.
     */
    fun openPostLink(post: CommunityPostCard) {
        val url = post.link ?: return
        _state.update { it.copy(urlToOpen = url) }
        sendContentEvent("posts/${post.postId}", "click")
    }

    private fun refillIfLow() {
        if (deck.size <= REFILL_THRESHOLD) {
            viewModelScope.launch { fetchMore() }
        }
    }

    // endregion

    // region Post detail sheet (poll votes, RSVPs)

    fun openPost(post: CommunityPostCard) {
        _state.update { it.copy(expandedPost = post) }
    }

    fun closePost() {
        _state.update { it.copy(expandedPost = null) }
    }

    fun vote(post: CommunityPostCard, optionId: String) {
        viewModelScope.launch { voteNow(post, optionId) }
    }

    fun rsvp(post: CommunityPostCard, going: Boolean) {
        viewModelScope.launch { rsvpNow(post, going) }
    }

    /**
     * Casts (or changes) a vote on a poll post, updating the matching deck
     * entry so the change survives dismissing and re-showing the card, and
     * the open detail sheet. Returns the updated post (null on failure).
     */
    internal suspend fun voteNow(post: CommunityPostCard, optionId: String): CommunityPostCard? =
        try {
            val result = api.vote(post.postId, optionId)
            post.copy(poll = result.poll, myVote = result.myVote).also(::replacePost)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e)
            null
        }

    /**
     * RSVPs (or cancels) for an event post, updating the matching deck entry
     * so the change survives dismissing and re-showing the card, and the open
     * detail sheet. Returns the updated post (null on failure).
     */
    internal suspend fun rsvpNow(post: CommunityPostCard, going: Boolean): CommunityPostCard? =
        try {
            val result = api.rsvp(post.postId, going)
            post.copy(attendeeCount = result.attendeeCount, myRsvp = result.going).also(::replacePost)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(e)
            null
        }

    private fun replacePost(updated: CommunityPostCard) {
        val id = "post-${updated.postId}"
        _state.update { state ->
            state.copy(
                deck = state.deck.map { if (it.id == id) DeckItem.Post(updated) else it },
                // iOS sets `expandedPost = updated` unconditionally, which re-presents a sheet
                // the user already closed; only refresh the sheet that's still showing it.
                expandedPost = if (state.expandedPost?.postId == updated.postId) updated else state.expandedPost,
            )
        }
    }

    // endregion

    // region Safety

    fun reportAndRemove(card: FeedCard, reason: String) {
        updateDeck { current -> current.filterNot { it.profileCard?.uid == card.uid } }
        viewModelScope.launch {
            try {
                api.report(card.uid, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    fun blockAndRemove(card: FeedCard) {
        updateDeck { current -> current.filterNot { it.profileCard?.uid == card.uid } }
        viewModelScope.launch {
            try {
                api.block(card.uid, card.displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    // endregion

    // region One-shot UI state

    fun dismissMatch() {
        _state.update { it.copy(matchedCard = null) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun consumeUrlToOpen() {
        _state.update { it.copy(urlToOpen = null) }
    }

    // endregion

    private fun showError(e: Throwable) {
        val message = e.userMessage()
        _state.update { it.copy(errorMessage = message) }
    }

    /**
     * Every deck mutation goes through here, so the top-card impression check
     * runs whenever the top may have changed (iOS calls it after each mutation
     * plus `.onChange(of: model.deck.first?.id)`).
     */
    private inline fun updateDeck(transform: (List<DeckItem>) -> List<DeckItem>) {
        val next = transform(deck)
        _state.update { it.copy(deck = next) }
        reportTopImpressionIfNeeded()
    }

    /**
     * Reports one impression per content card per session, the first time it
     * surfaces as the top card.
     */
    private fun reportTopImpressionIfNeeded() {
        val top = deck.firstOrNull() ?: return
        val path = when (top) {
            is DeckItem.Ad -> "ads/${top.ad.adId}"
            is DeckItem.News -> "news/${top.item.newsId}"
            is DeckItem.Post -> "posts/${top.post.postId}"
            is DeckItem.Profile -> return
        }
        if (!impressedContentIds.add(top.id)) return
        sendContentEvent(path, "impression")
    }
}

/**
 * One content queue, round-robined: returns the first item not already in
 * the deck, skipping a full lap if every item is already showing. The cursor
 * survives the queue being replaced by a newer page, like iOS.
 */
private class ContentQueue<T>(private val idOf: (T) -> String) {
    var items: List<T> = emptyList()
    private var cursor = 0

    fun next(excluding: Set<String>): T? {
        if (items.isEmpty()) return null
        repeat(items.size) {
            val item = items[cursor % items.size]
            cursor += 1
            if (idOf(item) !in excluding) return item
        }
        return null
    }
}
