package app.drokpo.android.features.chats

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import app.drokpo.android.core.model.FeedCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Port of ChatStore. A ViewModel created by MainTabs in the session scope (§D.3) and provided via
 * LocalChatStore. The constructor must not touch Firebase (the catalog instantiates it).
 *
 * (CONTRACT §B.6 — stub: holds state and keeps the derived flows consistent, but [start] attaches
 * no Firestore listener yet, so the lists stay empty in the app.)
 */
class ChatStore : ViewModel() {
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
    private val _isLoading = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _totalUnread = MutableStateFlow(0)
    private val _newMatches = MutableStateFlow<List<Entry>>(emptyList())
    private val _conversations = MutableStateFlow<List<Entry>>(emptyList())

    /** Sorted by sortDate desc. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    /** True until first snapshot + profile join. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    /** Sum of unread → Chats tab badge. */
    val totalUnread: StateFlow<Int> = _totalUnread.asStateFlow()
    /** !hasMessages */
    val newMatches: StateFlow<List<Entry>> = _newMatches.asStateFlow()
    /** hasMessages */
    val conversations: StateFlow<List<Entry>> = _conversations.asStateFlow()

    private var startedUid: String? = null

    /** Attach the Firestore listener for `uid`. No-op when already started for the same uid. */
    fun start(uid: String) {
        if (startedUid == uid) return
        stop()
        startedUid = uid
    }

    /** Remove the listener and reset all state (also called from onCleared()). */
    fun stop() {
        startedUid = null
        publish(emptyList())
        _isLoading.value = false
        _errorMessage.value = null
    }

    /** Optimistic local unread reset; the thread POSTs /matches/{id}/read. */
    fun clearUnread(matchId: String) {
        publish(_entries.value.map { if (it.matchId == matchId) it.copy(unread = 0) else it })
    }

    /** iOS `chats.errorMessage = …` (ChatsView unmatch failure) and alert dismissal (null). */
    fun setError(message: String?) {
        _errorMessage.value = message
    }

    override fun onCleared() {
        stop()
    }

    private fun publish(list: List<Entry>) {
        val sorted = list.sortedByDescending { it.sortDate }
        _entries.value = sorted
        _totalUnread.value = sorted.sumOf { it.unread }
        _newMatches.value = sorted.filter { !it.hasMessages }
        _conversations.value = sorted.filter { it.hasMessages }
    }
}

/** Provided by MainTabs (and by CatalogActivity with an idle ChatStore()). Reading it elsewhere throws. */
val LocalChatStore: ProvidableCompositionLocal<ChatStore> = staticCompositionLocalOf {
    error("LocalChatStore not provided — it is only available under MainTabs (or CatalogActivity).")
}
