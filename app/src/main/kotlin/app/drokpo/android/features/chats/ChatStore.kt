package app.drokpo.android.features.chats

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.Match
import app.drokpo.android.core.model.TolerantList
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Port of ChatStore: the real-time source for the chat list. A ViewModel created by MainTabs in the
 * session scope (§D.3) and provided via [LocalChatStore]. The constructor must not touch Firebase
 * (the catalog instantiates it).
 *
 * Match membership is decided by the backend (the swipe transaction), but once matches exist the
 * client listens to them directly — the Firestore rules allow participants to read their own match
 * docs — so previews and unread counts update live. Profiles of the other participants aren't
 * client-readable, so those come from the REST GET /api/matches, which joins them server-side.
 */
class ChatStore internal constructor(
    private val matchesListener: MatchesListener,
    /** GET /api/matches — only the joined `otherUser` profiles are used. */
    private val loadMatches: suspend () -> List<Match>,
    /** POST /api/matches/{id}/unmatch. */
    private val unmatchRequest: suspend (matchId: String) -> Unit,
) : ViewModel() {
    constructor() : this(
        matchesListener = FirestoreChats.matches,
        loadMatches = { ApiClient.get<TolerantList<Match>>("/api/matches").items },
        unmatchRequest = { matchId -> ApiClient.post<EmptyResponse>("/api/matches/$matchId/unmatch") },
    )

    data class Entry(
        val matchId: String,
        val otherUid: String,
        val otherUser: FeedCard? = null,
        val lastMessageText: String? = null,
        val lastMessageSenderId: String? = null,
        val unread: Int = 0,
        val sortDate: Instant = Instant.EPOCH,
    ) {
        val id: String get() = matchId
        val hasMessages: Boolean get() = lastMessageText != null
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    private val _isLoading = MutableStateFlow(true)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _totalUnread = MutableStateFlow(0)
    private val _newMatches = MutableStateFlow<List<Entry>>(emptyList())
    private val _conversations = MutableStateFlow<List<Entry>>(emptyList())
    private val _unmatching = MutableStateFlow<Set<String>>(emptySet())

    /** Sorted by sortDate desc. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** True until first snapshot + profile join. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Sum of unread → Chats tab badge. */
    val totalUnread: StateFlow<Int> = _totalUnread.asStateFlow()

    /** Matches with no conversation yet, newest first. */
    val newMatches: StateFlow<List<Entry>> = _newMatches.asStateFlow()

    /** Ongoing conversations, most recent message first. */
    val conversations: StateFlow<List<Entry>> = _conversations.asStateFlow()

    /**
     * Matches unmatched from the list's swipe action whose row is hidden while the request runs and
     * until the listener drops them (iOS animates a destructive swipe's row away at once). An id
     * leaves the set when the match leaves [entries], or when the request fails (the row comes back).
     */
    internal val unmatching: StateFlow<Set<String>> = _unmatching.asStateFlow()

    private var profiles: Map<String, FeedCard> = emptyMap()
    private var listener: ListenerHandle? = null
    private var uid: String? = null

    /** Bumped by stop(), so callbacks from a removed listener can never write into a new session. */
    private var generation = 0
    private var profileLoad: Job? = null

    /** A snapshot with missing profiles arrived while a GET /api/matches was already in flight. */
    private var profileReloadPending = false

    /** In-flight list unmatches, so a repeated call shares the request (and its outcome). */
    private val unmatchRequests = mutableMapOf<String, Deferred<Boolean>>()

    /** Attach the Firestore listener for `uid`. No-op when already started for the same uid. */
    fun start(uid: String) {
        if (this.uid == uid) return
        stop()
        this.uid = uid
        val session = generation
        listener = matchesListener.listen(
            uid,
            onMatches = { matches -> if (session == generation) apply(matches) },
            onError = { error ->
                if (session == generation) {
                    _errorMessage.value = error.userMessage()
                    _isLoading.value = false
                }
            },
        )
    }

    /** Remove the listener and reset all state (also called from onCleared()). */
    fun stop() {
        listener?.remove()
        listener = null
        uid = null
        generation++
        profileLoad?.cancel()
        profileLoad = null
        profileReloadPending = false
        unmatchRequests.clear()
        profiles = emptyMap()
        publish(emptyList())
        _unmatching.value = emptySet()
        _isLoading.value = true
        _errorMessage.value = null
    }

    /**
     * Optimistically clear the local unread badge; the server-side counter is reset by
     * POST /matches/{id}/read from the thread screen.
     */
    fun clearUnread(matchId: String) {
        val current = _entries.value
        if (current.none { it.matchId == matchId && it.unread != 0 }) return
        publish(current.map { if (it.matchId == matchId) it.copy(unread = 0) else it })
    }

    /** iOS `chats.errorMessage = …` (ChatsView unmatch failure) and alert dismissal (null). */
    fun setError(message: String?) {
        _errorMessage.value = message
    }

    /**
     * The list's swipe "Unmatch" (no confirmation, iOS parity): POST /api/matches/{id}/unmatch. The
     * Firestore listener drops the match automatically once its status flips to "unmatched"; until
     * then the row stays hidden ([unmatching]). A failure brings the row back and shows the alert.
     *
     * The result completes true once the request went through, false when it failed — the swipe row
     * waits for it to know whether to slide back. The request itself runs in this store's scope, so
     * it finishes even if the row leaves composition.
     */
    internal fun unmatch(matchId: String): Deferred<Boolean> {
        unmatchRequests[matchId]?.let { return it }
        // Already went through; the row stays hidden until the listener drops the match.
        if (matchId in _unmatching.value) return CompletableDeferred(true)
        _unmatching.update { it + matchId }
        val session = generation
        val request = viewModelScope.async(start = CoroutineStart.LAZY) {
            try {
                unmatchRequest(matchId)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session == generation) {
                    _unmatching.update { it - matchId }
                    _errorMessage.value = e.userMessage()
                }
                false
            }
        }
        unmatchRequests[matchId] = request
        request.invokeOnCompletion {
            if (unmatchRequests[matchId] === request) unmatchRequests.remove(matchId)
        }
        request.start()
        return request
    }

    override fun onCleared() {
        stop()
    }

    private fun apply(matches: List<Match>) {
        val uid = uid ?: return
        val built = buildEntries(matches, uid, profiles)
        publish(built.entries)
        _unmatching.update { ids -> ids.filterTo(mutableSetOf()) { id -> built.entries.any { it.matchId == id } } }
        if (built.missingProfiles) {
            loadProfiles()
        } else {
            _isLoading.value = false
        }
    }

    /**
     * GET /api/matches and join `otherUser` by uid. One request at a time: snapshots that arrive
     * meanwhile with profiles still missing trigger one more round once it finishes — also after a
     * failed round, as iOS starts a request per such snapshot and a later one can still succeed.
     * isLoading becomes false after every round, success or not (iOS parity).
     */
    private fun loadProfiles() {
        if (profileLoad?.isActive == true) {
            profileReloadPending = true
            return
        }
        val session = generation
        profileLoad = viewModelScope.launch {
            do {
                profileReloadPending = false
                try {
                    val list = loadMatches()
                    if (session != generation) return@launch
                    val updated = profiles.toMutableMap()
                    for (match in list) {
                        match.otherUser?.let { other -> updated[other.uid] = other }
                    }
                    profiles = updated
                    publish(_entries.value.map { it.copy(otherUser = updated[it.otherUid]) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (session != generation) return@launch
                    _errorMessage.value = e.userMessage()
                }
                _isLoading.value = false
                // profileReloadPending is only set by new snapshots, so this can't spin.
            } while (profileReloadPending && _entries.value.any { profiles[it.otherUid] == null })
        }
    }

    private fun publish(list: List<Entry>) {
        val sorted = list.sortedByDescending { it.sortDate }
        _entries.value = sorted
        _totalUnread.value = sorted.sumOf { it.unread }
        _newMatches.value = sorted.filter { !it.hasMessages }
        _conversations.value = sorted.filter { it.hasMessages }
    }

    internal data class BuiltEntries(val entries: List<Entry>, val missingProfiles: Boolean)

    internal companion object {
        /**
         * iOS `apply(documents:)`: one entry per match doc whose `users` holds someone other than
         * [uid] — `otherUid` is that user; `lastMessage.text` / `senderId` are the preview;
         * `unread` = `unreadCount[uid]`; `sortDate` = `lastMessage.createdAt` ?: `createdAt` ?:
         * the distant past. Sorted newest first. `missingProfiles` = some other user isn't in
         * [profiles] yet.
         */
        fun buildEntries(matches: List<Match>, uid: String, profiles: Map<String, FeedCard>): BuiltEntries {
            var missing = false
            val entries = matches.mapNotNull { match ->
                val otherUid = match.users?.firstOrNull { it != uid } ?: return@mapNotNull null
                val profile = profiles[otherUid]
                if (profile == null) missing = true
                Entry(
                    matchId = match.id,
                    otherUid = otherUid,
                    otherUser = profile,
                    lastMessageText = match.lastMessage?.text,
                    lastMessageSenderId = match.lastMessage?.senderId,
                    unread = match.unread(uid),
                    sortDate = match.sortDate,
                )
            }.sortedByDescending { it.sortDate }
            return BuiltEntries(entries, missing)
        }
    }
}

/** Provided by MainTabs (and by CatalogActivity with an idle ChatStore()). Reading it elsewhere throws. */
val LocalChatStore: ProvidableCompositionLocal<ChatStore> = staticCompositionLocalOf {
    error("LocalChatStore not provided — it is only available under MainTabs (or CatalogActivity).")
}
