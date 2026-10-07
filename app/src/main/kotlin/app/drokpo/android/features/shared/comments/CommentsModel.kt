package app.drokpo.android.features.shared.comments

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.model.CommentCard
import app.drokpo.android.core.model.CommentIn
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/** Everything [CommentsContent] renders — plain data, so the catalog can build it from fixtures. */
@Immutable
internal data class CommentsUiState(
    val myUid: String? = null,
    /** The post's owning community uid — lets the viewer delete any comment on their own post. */
    val postOwnerCid: String? = null,
    val comments: List<CommentCard> = emptyList(),
    val repliesByParent: Map<String, List<CommentCard>> = emptyMap(),
    val expandedParents: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    /** Pull-to-refresh in flight (iOS `.refreshable` shows its own spinner for the awaited load). */
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    /** The top-level comment the composer is replying to. */
    val replyTarget: CommentCard? = null,
    val isSending: Boolean = false,
    /**
     * One-shot: bumped when the list should scroll back to its first row — a
     * new top-level comment went in at the top, or a pull-to-refresh finished.
     */
    val scrollToTopToken: Int = 0,
) {
    /** Server-enforced too; this only drives the UI. */
    fun canDelete(comment: CommentCard): Boolean {
        val me = myUid ?: return false
        return comment.authorUid == me || me == postOwnerCid
    }

    fun isMine(comment: CommentCard): Boolean = myUid != null && comment.authorUid == myUid

    /**
     * Report/block is offered on everyone else's comments — never your own, and
     * not on an account-deletion tombstone, which has no author left to act on.
     */
    fun canReport(comment: CommentCard): Boolean = !isMine(comment) && comment.authorUid != null
}

/**
 * Network + list state for one post's comments — top-level comments plus
 * lazily-loaded reply threads, mirroring FeedModel's role for the deck. Port of
 * iOS `CommentsModel`; built fresh for every sheet presentation (§D.3), and its
 * first load starts immediately (iOS `.task`).
 */
internal class CommentsModel(
    val postId: String,
    postOwnerCid: String?,
    myUid: String?,
    private val repository: CommentsRepository = ApiCommentsRepository,
    /**
     * Where writes run (send, vote, delete, report, block). The sheet passes
     * AppGraph.appScope: iOS fires these in unstructured Tasks that finish even
     * when the sheet is closed right after a tap — a block or a just-sent comment
     * must not be cancelled by the dismissal. Null (tests) → viewModelScope.
     */
    private val writeScope: CoroutineScope? = null,
) : ViewModel() {
    private val writes: CoroutineScope get() = writeScope ?: viewModelScope

    private val _state = MutableStateFlow(CommentsUiState(myUid = myUid, postOwnerCid = postOwnerCid))
    val state: StateFlow<CommentsUiState> = _state.asStateFlow()

    /** Bumped by every full load, so a pagination page that raced a reload is dropped. */
    private var loadGeneration = 0
    private val votesInFlight = mutableSetOf<String>()
    private val deletesInFlight = mutableSetOf<String>()
    private val repliesInFlight = mutableSetOf<String>()

    init {
        viewModelScope.launch { load() }
    }

    // region Loading

    /**
     * Full (re)load of the first page. The spinner only shows while the list is
     * still empty. [scrollToTop] (pull-to-refresh) brings the list back to its
     * first row once the fresh page is in.
     */
    suspend fun load(scrollToTop: Boolean = false) {
        loadGeneration++
        _state.update { it.copy(isLoading = it.comments.isEmpty(), hasMore = true) }
        try {
            val fresh = repository.comments(postId, before = null).distinctBy { it.commentId }
            _state.update { st ->
                st.copy(
                    comments = fresh,
                    hasMore = fresh.isNotEmpty(),
                    scrollToTopToken = if (scrollToTop) st.scrollToTopToken + 1 else st.scrollToTopToken,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = e.userMessage()) }
        }
        _state.update { it.copy(isLoading = false) }
    }

    /** Pull-to-refresh. */
    fun refresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                load(scrollToTop = true)
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /** Called when a row appears; pages in older comments once the last one is on screen. */
    fun loadMoreIfNeeded(current: CommentCard) {
        val state = _state.value
        if (!state.hasMore || state.isLoadingMore || state.isLoading) return
        if (current.commentId != state.comments.lastOrNull()?.commentId) return
        val generation = loadGeneration
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            try {
                val fresh = repository.comments(postId, before = current.commentId)
                if (generation == loadGeneration) {
                    _state.update { st ->
                        val known = st.comments.mapTo(HashSet()) { it.commentId }
                        st.copy(
                            comments = st.comments + fresh.filter { it.commentId !in known }.distinctBy { it.commentId },
                            hasMore = fresh.isNotEmpty(),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent — a pagination hiccup shouldn't interrupt reading comments.
            } finally {
                _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    // endregion

    // region Replies

    /**
     * Expands a thread, loading its replies the first time (the UI offers no
     * collapse control once expanded — iOS parity — but the toggle stays symmetric).
     */
    fun toggleReplies(parentId: String) {
        val state = _state.value
        if (parentId in state.expandedParents) {
            _state.update { it.copy(expandedParents = it.expandedParents - parentId) }
            return
        }
        _state.update { it.copy(expandedParents = it.expandedParents + parentId) }
        if (state.repliesByParent[parentId] != null) return
        viewModelScope.launch { fetchReplies(parentId, reportFailure = true) }
    }

    /**
     * Loads one thread and merges it with any replies already shown locally (a
     * reply posted while the fetch was in flight). On failure the thread
     * collapses again so "View n replies" can retry — iOS left a spinner that
     * never went away.
     */
    private suspend fun fetchReplies(parentId: String, reportFailure: Boolean) {
        if (!repliesInFlight.add(parentId)) return
        try {
            val fetched = repository.replies(postId, parentId).distinctBy { it.commentId }
            _state.update { st ->
                val fetchedIds = fetched.mapTo(HashSet()) { it.commentId }
                val local = st.repliesByParent[parentId].orEmpty().filter { it.commentId !in fetchedIds }
                st.copy(repliesByParent = st.repliesByParent + (parentId to fetched + local))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reportFailure) {
                _state.update { st ->
                    st.copy(
                        errorMessage = e.userMessage(),
                        expandedParents = if (st.repliesByParent[parentId] == null) st.expandedParents - parentId else st.expandedParents,
                    )
                }
            }
        } finally {
            repliesInFlight.remove(parentId)
        }
    }

    fun setReplyTarget(comment: CommentCard) {
        _state.update { it.copy(replyTarget = comment) }
    }

    fun cancelReply() {
        _state.update { it.copy(replyTarget = null) }
    }

    // endregion

    // region Composer

    /**
     * Uploads audio if needed, posts the comment, and inserts it locally (top
     * of the list for a top-level comment, end of the thread for a reply).
     * [onSuccess] lets the composer clear itself — it keeps the draft on failure.
     */
    fun submit(draft: CommentDraft, onSuccess: () -> Unit) {
        if (_state.value.isSending) return
        val parentId = _state.value.replyTarget?.commentId
        _state.update { it.copy(isSending = true) }
        writes.launch {
            val succeeded = try {
                submitNow(draft, parentId)
            } finally {
                _state.update { it.copy(isSending = false) }
            }
            if (succeeded) {
                _state.update { st -> if (st.replyTarget?.commentId == parentId) st.copy(replyTarget = null) else st }
                onSuccess()
            }
        }
    }

    /** Returns whether it succeeded; errors land in [CommentsUiState.errorMessage]. */
    internal suspend fun submitNow(draft: CommentDraft, parentId: String?): Boolean {
        try {
            val payload = when (draft) {
                is CommentDraft.Text -> {
                    val text = draft.text.trim()
                    if (text.isEmpty()) return false
                    CommentIn(text = text, parentId = parentId)
                }
                is CommentDraft.Audio -> {
                    val storagePath = repository.uploadCommentAudio(draft.file)
                    CommentIn(audioStoragePath = storagePath, audioDurationSec = draft.seconds, parentId = parentId)
                }
            }
            val created = repository.create(postId, payload)
            if (parentId != null) {
                insertReply(created, parentId)
            } else {
                _state.update { st ->
                    st.copy(
                        comments = listOf(created) + st.comments.filter { it.commentId != created.commentId },
                        scrollToTopToken = st.scrollToTopToken + 1,
                    )
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = e.userMessage()) }
            return false
        }
    }

    private fun insertReply(created: CommentCard, parentId: String) {
        val previous = _state.value
        val threadWasLoaded = previous.repliesByParent[parentId] != null
        val hadOtherReplies = (previous.findComment(parentId)?.replyCount ?: 0) > 0
        _state.update { st ->
            val thread = st.repliesByParent[parentId].orEmpty().filter { it.commentId != created.commentId } + created
            st.copy(
                repliesByParent = st.repliesByParent + (parentId to thread),
                expandedParents = st.expandedParents + parentId,
            ).bumpReplyCount(parentId, by = 1)
        }
        // iOS showed only the new reply in a thread it had never loaded, hiding
        // the existing ones for good (the "View n replies" button is gone once
        // expanded). Fill the thread in from the server instead — in the
        // background, so the send spinner and the composer don't wait on it
        // (iOS returns right after the local insert). It only matters while the
        // sheet is open, hence viewModelScope rather than the write scope.
        if (!threadWasLoaded && hadOtherReplies) {
            viewModelScope.launch { fetchReplies(parentId, reportFailure = false) }
        }
    }

    // endregion

    // region Row actions

    fun delete(comment: CommentCard) {
        val id = comment.commentId
        if (!deletesInFlight.add(id)) return
        writes.launch {
            try {
                repository.delete(postId, id)
                _state.update { st ->
                    val parentId = comment.parentId
                    val updated = if (parentId != null) {
                        val replies = st.repliesByParent[parentId]
                        st.copy(
                            repliesByParent = if (replies != null) {
                                st.repliesByParent + (parentId to replies.filter { it.commentId != id })
                            } else {
                                st.repliesByParent
                            },
                        ).bumpReplyCount(parentId, by = -1)
                    } else {
                        st.copy(
                            comments = st.comments.filter { it.commentId != id },
                            repliesByParent = st.repliesByParent - id,
                            expandedParents = st.expandedParents - id,
                        )
                    }
                    // Replying to a comment that no longer exists would only fail server-side.
                    if (updated.replyTarget?.commentId == id) updated.copy(replyTarget = null) else updated
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            } finally {
                deletesInFlight.remove(id)
            }
        }
    }

    /**
     * [value] is "like", "dislike", or null to clear the vote. The counts move
     * immediately and are then reconciled with the server's; a failure rolls
     * them back. Taps on a comment whose vote is still in flight are ignored,
     * so PUT/DELETE responses can't land out of order.
     */
    fun vote(comment: CommentCard, value: String?) {
        val id = comment.commentId
        if (!votesInFlight.add(id)) return
        val before = _state.value.findComment(id) ?: comment
        _state.update { it.updateComment(id) { current -> current.withVote(value) } }
        writes.launch {
            try {
                val result = repository.vote(postId, id, value)
                _state.update {
                    it.updateComment(id) { current ->
                        current.copy(likeCount = result.likeCount, dislikeCount = result.dislikeCount, myVote = result.myVote)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.updateComment(id) { current ->
                        current.copy(likeCount = before.likeCount, dislikeCount = before.dislikeCount, myVote = before.myVote)
                    }.copy(errorMessage = e.userMessage())
                }
            } finally {
                votesInFlight.remove(id)
            }
        }
    }

    fun reportAuthor(comment: CommentCard, reason: String) {
        val authorUid = comment.authorUid ?: return
        writes.launch {
            try {
                repository.report(authorUid, reason, reportNote(comment))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            }
        }
    }

    internal fun reportNote(comment: CommentCard): String = "Comment ${comment.commentId} on post $postId"

    /**
     * Blocks the comment's author, then reloads — the backend drops blocked
     * authors' comments from the list, so their content disappears too. Their
     * replies already loaded into open threads are dropped locally as well
     * (a reload only replaces the top-level page).
     */
    fun blockAuthor(comment: CommentCard) {
        val authorUid = comment.authorUid ?: return
        writes.launch {
            try {
                repository.block(authorUid, comment.authorName)
                _state.update { st ->
                    st.copy(
                        repliesByParent = st.repliesByParent.mapValues { (_, replies) -> replies.filter { it.authorUid != authorUid } },
                        replyTarget = st.replyTarget?.takeIf { it.authorUid != authorUid },
                    )
                }
                // The reload is only for this sheet — skipped once it has closed.
                viewModelScope.launch { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    // endregion
}

// region Pure state helpers (unit-tested)

/** Top-level comments first, then every loaded thread — the iOS `update(_:_:)` search order. */
internal fun CommentsUiState.findComment(commentId: String): CommentCard? =
    comments.firstOrNull { it.commentId == commentId }
        ?: repliesByParent.values.firstNotNullOfOrNull { replies -> replies.firstOrNull { it.commentId == commentId } }

internal fun CommentsUiState.updateComment(commentId: String, mutate: (CommentCard) -> CommentCard): CommentsUiState {
    val index = comments.indexOfFirst { it.commentId == commentId }
    if (index >= 0) {
        return copy(comments = comments.toMutableList().also { it[index] = mutate(it[index]) })
    }
    for ((parentId, replies) in repliesByParent) {
        val replyIndex = replies.indexOfFirst { it.commentId == commentId }
        if (replyIndex < 0) continue
        val updated = replies.toMutableList().also { it[replyIndex] = mutate(it[replyIndex]) }
        return copy(repliesByParent = repliesByParent + (parentId to updated))
    }
    return this
}

internal fun CommentsUiState.bumpReplyCount(parentId: String, by: Int): CommentsUiState =
    updateComment(parentId) { it.copy(replyCount = max(0, (it.replyCount ?: 0) + by)) }

/** The optimistic counts after switching this comment's vote to [value] ("like" / "dislike" / null). */
internal fun CommentCard.withVote(value: String?): CommentCard {
    var likes = likeCount ?: 0
    var dislikes = dislikeCount ?: 0
    when (myVote) {
        VOTE_LIKE -> likes -= 1
        VOTE_DISLIKE -> dislikes -= 1
    }
    when (value) {
        VOTE_LIKE -> likes += 1
        VOTE_DISLIKE -> dislikes += 1
    }
    return copy(likeCount = max(0, likes), dislikeCount = max(0, dislikes), myVote = value)
}

/** What tapping a vote button sends: the active vote clears, anything else switches to it. */
internal fun nextVote(myVote: String?, tapped: String): String? = if (myVote == tapped) null else tapped

/** "View 1 reply" / "View 3 replies". */
internal fun viewRepliesLabel(count: Int): String = "View $count repl${if (count == 1) "y" else "ies"}"

internal const val VOTE_LIKE = "like"
internal const val VOTE_DISLIKE = "dislike"

// endregion
