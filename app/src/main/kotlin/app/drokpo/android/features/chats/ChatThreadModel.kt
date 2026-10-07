package app.drokpo.android.features.chats

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.MediaUploader
import app.drokpo.android.core.Safety
import app.drokpo.android.core.tryOrNull
import app.drokpo.android.core.userMessage
import app.drokpo.android.features.shared.audio.RecordedClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What the input bar is about to send — a message carries exactly one. */
internal sealed interface ChatDraft {
    data class Text(val text: String) : ChatDraft
    data class Photo(val uri: Uri) : ChatDraft
    data class Voice(val file: File, val seconds: Int) : ChatDraft
}

/** The thread's toolbar-menu confirmations (iOS `.confirmationDialog`s). */
internal enum class ThreadDialog { Unmatch, Block, Report }

/**
 * State of one ChatThreadView: the live messages listener, read receipts, the input bar (draft,
 * recorded clip, sending), and the unmatch / block / report actions. One per `ChatsRoute.Thread`
 * back-stack entry, so — like iOS `@State` of the pushed view — it survives a pushed profile and
 * tab switches, where the thread's composition is disposed.
 *
 * The match entry itself (other user, unread count) is not copied here: it is looked up by id in
 * [entries] whenever it is needed, so unread/profile stay current — and it is null during a push
 * deep-link cold start, before the ChatStore listener has delivered this match.
 */
internal class ChatThreadModel(
    val matchId: String,
    private val myUid: String?,
    private val entries: StateFlow<List<ChatStore.Entry>>,
    private val clearUnread: (matchId: String) -> Unit,
    private val messagesListener: MessagesListener = FirestoreChats.messages,
    private val writer: MessageWriter = FirestoreChats.writer,
    private val uploadPhoto: suspend (Uri) -> String = { MediaUploader.uploadChatPhoto(it) },
    private val uploadAudio: suspend (File) -> String = { MediaUploader.uploadChatAudio(it) },
    private val markReadRequest: suspend (matchId: String) -> Unit = { id ->
        ApiClient.post<EmptyResponse>("/api/matches/$id/read")
    },
    private val unmatchRequest: suspend (matchId: String) -> Unit = { id ->
        ApiClient.post<EmptyResponse>("/api/matches/$id/unmatch")
    },
    private val blockRequest: suspend (uid: String, displayName: String?) -> Unit = { uid, name ->
        Safety.block(uid, name)
    },
    private val reportRequest: suspend (uid: String, reason: String) -> Unit = { uid, reason ->
        Safety.report(uid, reason)
    },
    /**
     * Where uploads, message writes, read receipts and the thread actions run. iOS fires them from
     * unstructured Tasks that finish even after the thread is closed; the app scope gives the same
     * lifetime (CONTRACT §0.4). Their results are written into this model even if it was cleared
     * meanwhile, which is harmless.
     */
    private val backgroundScope: CoroutineScope = AppGraph.appScope,
) : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _closed = MutableStateFlow(false)
    private val _recordedClip = MutableStateFlow<RecordedClip?>(null)
    private val _isSending = MutableStateFlow(false)

    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** Thread errors and the input bar's (send failures, unreadable photo) — one alert. */
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * The input bar's text (iOS ChatInputBar `@State draft`). Snapshot state rather than a
     * StateFlow so the text field reads its edits back synchronously (an async collect can drop
     * or reorder IME input).
     */
    var draft: String by mutableStateOf("")
        private set

    /** A finished recording waiting to be sent or deleted (iOS `recordedFile`). */
    val recordedClip: StateFlow<RecordedClip?> = _recordedClip.asStateFlow()

    /** A message is being uploaded / written — the bar shows its spinner (iOS `isSending`). */
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    /** True once an unmatch or block went through — the screen then pops (iOS `dismiss()`). */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    private var registration: ListenerHandle? = null

    /**
     * Last message id we've sent a read receipt for, so snapshot churn doesn't spam POST /read.
     */
    private var lastMarkedMessageId: String? = null

    private val entry: ChatStore.Entry?
        get() = entries.value.firstOrNull { it.matchId == matchId }

    /**
     * iOS `.onAppear { attachListener() }`. The screen calls it when its back-stack entry starts
     * (visible again after a pushed profile is popped, or the app returns to the foreground).
     */
    fun attach() {
        if (registration != null) return
        registration = messagesListener.listen(
            matchId,
            onMessages = { list ->
                _messages.value = list
                markRead()
            },
            onError = { error -> _errorMessage.value = error.userMessage() },
        )
    }

    /**
     * iOS `.onDisappear`: remove the listener. Also on Android when the app goes to the background,
     * so incoming messages aren't marked read while nobody is looking at the thread.
     */
    fun detach() {
        registration?.remove()
        registration = null
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    private fun markRead() {
        // Reads the live entry (not a stale captured copy), so the unread guard reflects the
        // current count.
        val target = readReceiptTarget(
            unread = entry?.unread ?: 0,
            messages = _messages.value,
            myUid = myUid,
            lastMarkedMessageId = lastMarkedMessageId,
        ) ?: return
        lastMarkedMessageId = target
        clearUnread(matchId)
        backgroundScope.launch { tryOrNull { markReadRequest(matchId) } }
    }

    fun updateDraft(text: String) {
        draft = text
    }

    /** The recorder stopped (by the user or at the 120 s cap): show the clip preview. */
    fun setRecordedClip(clip: RecordedClip) {
        _recordedClip.value = clip
    }

    /** The preview's trash button: delete the file and go back to the text field. */
    fun discardClip() {
        _recordedClip.value?.file?.delete()
        _recordedClip.value = null
    }

    /**
     * The send button (iOS `sendCurrentDraft`): the recorded clip if there is one, else the trimmed
     * text. Afterwards — success or failure, as on iOS — the clip or the text is cleared.
     */
    fun sendCurrentDraft() {
        if (_isSending.value) return
        val clip = _recordedClip.value
        val text = draft.trim()
        if (clip == null && text.isEmpty()) return
        _isSending.value = true
        backgroundScope.launch {
            try {
                if (clip != null) {
                    deliver(ChatDraft.Voice(clip.file, clip.seconds))
                    _recordedClip.compareAndSet(clip, null)
                } else {
                    deliver(ChatDraft.Text(text))
                    draft = ""
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    /**
     * A photo was picked (iOS `sendPhoto`): sent at once if [canLoad] can read it, otherwise the
     * "couldn't be loaded" alert. The spinner shows meanwhile.
     */
    fun sendPhoto(photo: ChatDraft.Photo, canLoad: suspend (Uri) -> Boolean) {
        if (_isSending.value) return
        _isSending.value = true
        backgroundScope.launch {
            try {
                if (canLoad(photo.uri)) {
                    deliver(photo)
                } else {
                    _errorMessage.value = PHOTO_LOAD_FAILED
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    /**
     * Uploads media (if any) then writes the message via direct Firestore write (see
     * [FirestoreChats.writer]). Errors land in [errorMessage]. The work runs on [backgroundScope]
     * and reports its error itself, so cancelling the caller only stops the waiting.
     */
    suspend fun send(draft: ChatDraft) {
        backgroundScope.launch { deliver(draft) }.join()
    }

    private suspend fun deliver(draft: ChatDraft) {
        val uid = myUid ?: return
        try {
            val message = when (draft) {
                is ChatDraft.Text -> {
                    val trimmed = draft.text.trim()
                    if (trimmed.isEmpty()) return
                    OutgoingMessage(text = trimmed)
                }
                is ChatDraft.Photo -> OutgoingMessage(text = PHOTO_PLACEHOLDER, imageUrl = uploadPhoto(draft.uri))
                is ChatDraft.Voice -> OutgoingMessage(
                    text = VOICE_PLACEHOLDER,
                    audioUrl = uploadAudio(draft.file),
                    audioDurationSec = draft.seconds,
                )
            }
            writer.add(matchId, uid, message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errorMessage.value = e.userMessage()
        }
    }

    /** "Unmatch?" → POST /api/matches/{id}/unmatch, then dismiss. */
    fun unmatch() {
        runAction(closeOnSuccess = true) { unmatchRequest(matchId) }
    }

    /** "Block this person?" → POST /api/blocks/{uid} + local BlockStore record, then dismiss. */
    fun block() {
        val current = entry ?: return
        runAction(closeOnSuccess = true) { blockRequest(current.otherUid, current.otherUser?.displayName) }
    }

    /** "Report this person" → POST /api/reports {reportedUid, reason, note: ""}. No dismissal. */
    fun report(reason: String) {
        val current = entry ?: return
        runAction(closeOnSuccess = false) { reportRequest(current.otherUid, reason) }
    }

    /** On [backgroundScope], so leaving the thread mid-request doesn't cancel it (iOS `Task {}`). */
    private fun runAction(closeOnSuccess: Boolean, request: suspend () -> Unit) {
        backgroundScope.launch {
            try {
                request()
                if (closeOnSuccess) _closed.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = e.userMessage()
            }
        }
    }

    override fun onCleared() {
        detach()
        // A clip nobody sent or deleted; one being sent is still needed by the upload.
        if (!_isSending.value) _recordedClip.value?.file?.delete()
    }

    companion object {
        const val PHOTO_PLACEHOLDER = "📷 Photo"
        const val VOICE_PLACEHOLDER = "🎤 Voice message"

        /** iOS ChatInputBar: voice messages are capped at 120 s (comments use 60 s). */
        const val MAX_VOICE_SECONDS = 120

        /**
         * The message id to send a read receipt for, or null to skip. Skips unless there is
         * something unread or the last message isn't ours; and — since the listener fires on every
         * snapshot (including our own sends and presence-style updates) — only POSTs when there's
         * actually a new last message since the last read receipt.
         */
        fun readReceiptTarget(
            unread: Int,
            messages: List<ChatMessage>,
            myUid: String?,
            lastMarkedMessageId: String?,
        ): String? {
            val last = messages.lastOrNull()
            if (unread <= 0 && last?.senderId == myUid) return null
            val lastId = last?.id ?: return null
            return lastId.takeIf { it != lastMarkedMessageId }
        }
    }
}
