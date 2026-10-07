package app.drokpo.android.features.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.drokpo.android.core.ApiError
import app.drokpo.android.core.PhotoUploaderError
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.features.shared.audio.RecordedClip
import com.google.firebase.Timestamp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadModelTest {
    private val me = "me"
    private val pema = FeedCard(uid = "pema", displayName = "Pema")

    private class FakeMessages : MessagesListener {
        var listens = 0
        var removed = 0
        private var onMessages: ((List<ChatMessage>) -> Unit)? = null
        private var onError: ((Exception) -> Unit)? = null

        override fun listen(matchId: String, onMessages: (List<ChatMessage>) -> Unit, onError: (Exception) -> Unit): ListenerHandle {
            listens++
            this.onMessages = onMessages
            this.onError = onError
            return ListenerHandle { removed++ }
        }

        fun emit(vararg messages: ChatMessage) = onMessages!!(messages.toList())
        fun fail(error: Exception) = onError!!(error)
    }

    private data class Written(val matchId: String, val senderId: String, val message: OutgoingMessage)

    private val listener = FakeMessages()
    private val entries = MutableStateFlow(listOf(ChatStore.Entry(matchId = "m1", otherUid = "pema", otherUser = pema, unread = 0)))
    private val cleared = mutableListOf<String>()
    private val readReceipts = mutableListOf<String>()
    private val written = mutableListOf<Written>()
    private val calls = mutableListOf<String>()
    private var writeError: Exception? = null
    private var uploadError: Exception? = null
    private var actionError: Exception? = null
    private var writeGate: CompletableDeferred<Unit>? = null
    private var photoGate: CompletableDeferred<String>? = null
    private var actionGate: CompletableDeferred<Unit>? = null

    private fun TestScope.model(myUid: String? = me) = ChatThreadModel(
        matchId = "m1",
        myUid = myUid,
        entries = entries,
        clearUnread = { cleared += it },
        messagesListener = listener,
        writer = { matchId, senderId, message ->
            writeGate?.await()
            writeError?.let { throw it }
            written += Written(matchId, senderId, message)
        },
        uploadPhoto = { photoGate?.await() ?: uploadError?.let { throw it } ?: "https://storage/photo.jpg" },
        uploadAudio = { file -> uploadError?.let { throw it } ?: "https://storage/${file.name}" },
        markReadRequest = { readReceipts += it },
        unmatchRequest = { id ->
            actionGate?.await()
            actionError?.let { throw it }
            calls += "unmatch:$id"
        },
        blockRequest = { uid, name ->
            actionGate?.await()
            actionError?.let { throw it }
            calls += "block:$uid:$name"
        },
        reportRequest = { uid, reason ->
            actionGate?.await()
            actionError?.let { throw it }
            calls += "report:$uid:$reason"
        },
        backgroundScope = backgroundScope,
    )

    /**
     * android.net.Uri is a public-API stub in JVM tests (no instances can be built), so allocate the
     * draft without running its constructor; the fake uploader never looks at the uri.
     */
    private fun photoDraft(): ChatDraft.Photo {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ChatDraft.Photo::class.java) as ChatDraft.Photo
    }

    /** Registers [model] in a ViewModelStore, so a test can clear it like a popped back-stack entry. */
    private fun storeOf(model: ChatThreadModel): ViewModelStore {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
        }
        ViewModelProvider(store, factory)[ChatThreadModel::class.java]
        return store
    }

    private fun clip(seconds: Int = 9): RecordedClip =
        RecordedClip(File.createTempFile("chat-clip", ".m4a").apply { deleteOnExit() }, seconds)

    private fun message(id: String, sender: String, text: String = "hi", seconds: Long = 0) =
        ChatMessage(id, sender, text, createdAt = Instant.ofEpochSecond(1_000 + seconds))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region Listener lifecycle

    @Test
    fun attachIsIdempotentAndDetachRemoves() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.attach()
        model.attach()
        assertEquals(1, listener.listens)
        model.detach()
        assertEquals(1, listener.removed)
        model.attach() // back on screen (iOS onAppear)
        assertEquals(2, listener.listens)
    }

    @Test
    fun snapshotsReplaceMessagesAndErrorsSurface() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.attach()
        listener.emit(message("a", "pema"), message("b", me))
        assertEquals(listOf("a", "b"), model.messages.value.map { it.id })
        listener.fail(IllegalStateException("Missing or insufficient permissions."))
        assertEquals("Missing or insufficient permissions.", model.errorMessage.value)
        model.dismissError()
        assertNull(model.errorMessage.value)
    }

    // endregion

    // region Read receipts

    @Test
    fun incomingMessageIsMarkedReadOncePerLastMessage() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.attach()
        listener.emit(message("a", "pema"))
        assertEquals(listOf("m1"), cleared)
        assertEquals(listOf("m1"), readReceipts)

        // Snapshot churn with the same last message doesn't POST again.
        listener.emit(message("a", "pema"))
        assertEquals(1, readReceipts.size)

        // A new incoming message does.
        listener.emit(message("a", "pema"), message("b", "pema"))
        assertEquals(2, readReceipts.size)
    }

    @Test
    fun ownLastMessageSkipsUnlessSomethingIsUnread() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.attach()
        listener.emit(message("a", "pema"), message("b", me))
        assertEquals(emptyList<String>(), readReceipts)

        entries.value = listOf(entries.value.single().copy(unread = 2))
        listener.emit(message("a", "pema"), message("b", me))
        assertEquals(listOf("m1"), readReceipts)
        assertEquals(listOf("m1"), cleared)
    }

    @Test
    fun readReceiptTargetRules() {
        val theirs = message("t", "pema")
        val mine = message("m", me)
        assertEquals("t", ChatThreadModel.readReceiptTarget(0, listOf(theirs), me, null))
        assertNull(ChatThreadModel.readReceiptTarget(0, listOf(theirs), me, "t"))
        assertNull(ChatThreadModel.readReceiptTarget(0, listOf(mine), me, null))
        assertEquals("m", ChatThreadModel.readReceiptTarget(1, listOf(mine), me, null))
        assertNull(ChatThreadModel.readReceiptTarget(3, emptyList(), me, null))
        assertNull(ChatThreadModel.readReceiptTarget(0, emptyList(), null, null))
    }

    // endregion

    // region Sending

    @Test
    fun textIsTrimmedAndBlankIsNotSent() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.send(ChatDraft.Text("  Tashi delek!\n"))
        model.send(ChatDraft.Text(" \n "))
        assertEquals(listOf(Written("m1", me, OutgoingMessage(text = "Tashi delek!"))), written)
        assertNull(model.errorMessage.value)
    }

    @Test
    fun photoUploadsThenWritesThePlaceholderText() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.send(photoDraft())
        assertEquals(
            OutgoingMessage(text = "📷 Photo", imageUrl = "https://storage/photo.jpg"),
            written.single().message,
        )
    }

    @Test
    fun voiceUploadsThenWritesUrlAndDuration() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.send(ChatDraft.Voice(File("clip.m4a"), seconds = 12))
        assertEquals(
            OutgoingMessage(text = "🎤 Voice message", audioUrl = "https://storage/clip.m4a", audioDurationSec = 12),
            written.single().message,
        )
    }

    @Test
    fun uploadFailureShowsTheErrorAndWritesNothing() = runTest(UnconfinedTestDispatcher()) {
        uploadError = PhotoUploaderError.InvalidImage()
        val model = model()
        model.send(photoDraft())
        assertEquals(emptyList<Written>(), written)
        assertEquals("That photo couldn't be processed. Try a different one.", model.errorMessage.value)
    }

    @Test
    fun writeFailureShowsTheError() = runTest(UnconfinedTestDispatcher()) {
        writeError = IllegalStateException("PERMISSION_DENIED: Missing or insufficient permissions.")
        val model = model()
        model.send(ChatDraft.Text("hello"))
        assertEquals("PERMISSION_DENIED: Missing or insufficient permissions.", model.errorMessage.value)
    }

    @Test
    fun nothingIsSentWithoutASignedInUser() = runTest(UnconfinedTestDispatcher()) {
        val model = model(myUid = null)
        model.send(ChatDraft.Text("hello"))
        assertEquals(emptyList<Written>(), written)
    }

    @Test
    fun aFailedSendStillAlertsWhenTheCallerIsCancelled() = runTest(UnconfinedTestDispatcher()) {
        // The input bar leaves composition (tab switch, pushed profile) mid-upload.
        photoGate = CompletableDeferred()
        val model = model()
        val caller = launch { model.send(photoDraft()) }
        caller.cancel()
        photoGate!!.completeExceptionally(PhotoUploaderError.InvalidImage())
        assertEquals("That photo couldn't be processed. Try a different one.", model.errorMessage.value)
        assertEquals(emptyList<Written>(), written)
    }

    @Test
    fun sendCurrentDraftSendsTheTrimmedTextOnceThenClearsIt() = runTest(UnconfinedTestDispatcher()) {
        writeGate = CompletableDeferred()
        val model = model()
        model.updateDraft("  On my way! ")
        model.sendCurrentDraft()
        assertTrue(model.isSending.value)
        model.sendCurrentDraft() // the send button is disabled meanwhile
        writeGate!!.complete(Unit)
        assertEquals(listOf(OutgoingMessage(text = "On my way!")), written.map { it.message })
        assertEquals("", model.draft)
        assertFalse(model.isSending.value)
    }

    @Test
    fun aFailedTextSendStillClearsTheDraft() = runTest(UnconfinedTestDispatcher()) {
        // iOS: `await onSend(.text(text)); draft = ""` — onSend reports the error itself.
        writeError = IllegalStateException("PERMISSION_DENIED: Missing or insufficient permissions.")
        val model = model()
        model.updateDraft("hello")
        model.sendCurrentDraft()
        assertEquals("", model.draft)
        assertEquals("PERMISSION_DENIED: Missing or insufficient permissions.", model.errorMessage.value)
        assertFalse(model.isSending.value)
    }

    @Test
    fun aRecordedClipIsSentInsteadOfTheText() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        val clip = clip(seconds = 21)
        model.updateDraft("typed before recording")
        model.setRecordedClip(clip)
        model.sendCurrentDraft()
        assertEquals(
            listOf(OutgoingMessage(text = "🎤 Voice message", audioUrl = "https://storage/${clip.file.name}", audioDurationSec = 21)),
            written.map { it.message },
        )
        assertNull(model.recordedClip.value)
        assertEquals("typed before recording", model.draft)
    }

    @Test
    fun aBlankDraftSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.updateDraft(" \n ")
        model.sendCurrentDraft()
        assertEquals(emptyList<Written>(), written)
        assertFalse(model.isSending.value)
    }

    @Test
    fun aPickedPhotoIsSentOnlyIfItCanBeRead() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.sendPhoto(photoDraft()) { false }
        assertEquals(PHOTO_LOAD_FAILED, model.errorMessage.value)
        assertEquals(emptyList<Written>(), written)
        assertFalse(model.isSending.value)

        model.dismissError()
        photoGate = CompletableDeferred()
        model.sendPhoto(photoDraft()) { true }
        assertTrue(model.isSending.value)
        photoGate!!.complete("https://storage/picked.jpg")
        assertEquals(listOf(OutgoingMessage(text = "📷 Photo", imageUrl = "https://storage/picked.jpg")), written.map { it.message })
        assertFalse(model.isSending.value)
        assertNull(model.errorMessage.value)
    }

    @Test
    fun discardingTheClipDeletesItsFile() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        val clip = clip()
        model.setRecordedClip(clip)
        model.discardClip()
        assertNull(model.recordedClip.value)
        assertFalse(clip.file.exists())
    }

    @Test
    fun clearingTheModelDeletesAnUnsentClipButNotOneBeingUploaded() = runTest(UnconfinedTestDispatcher()) {
        val idle = model()
        val unsent = clip()
        idle.setRecordedClip(unsent)
        storeOf(idle).clear()
        assertFalse(unsent.file.exists())

        writeGate = CompletableDeferred()
        val sending = model()
        val uploading = clip()
        sending.setRecordedClip(uploading)
        sending.sendCurrentDraft()
        storeOf(sending).clear() // the user left the thread mid-send
        assertTrue(uploading.file.exists())
        writeGate!!.complete(Unit)
        assertEquals(1, written.size)
    }

    @Test
    fun firestoreDocumentCarriesExplicitNulls() {
        val sentinel = Any()
        val data = OutgoingMessage(text = "hello").firestoreData(senderId = me, createdAt = sentinel)
        assertEquals(
            setOf("senderId", "text", "imageUrl", "audioUrl", "audioDurationSec", "createdAt", "readAt"),
            data.keys,
        )
        assertEquals(me, data["senderId"])
        assertEquals("hello", data["text"])
        assertTrue(data.containsKey("imageUrl") && data["imageUrl"] == null)
        assertTrue(data.containsKey("audioUrl") && data["audioUrl"] == null)
        assertTrue(data.containsKey("audioDurationSec") && data["audioDurationSec"] == null)
        assertTrue(data.containsKey("readAt") && data["readAt"] == null)
        assertTrue(data["createdAt"] === sentinel)

        val voice = OutgoingMessage("🎤 Voice message", audioUrl = "https://a", audioDurationSec = 7).firestoreData(me, sentinel)
        assertEquals("https://a", voice["audioUrl"])
        assertEquals(7, voice["audioDurationSec"])
    }

    // endregion

    // region Thread actions

    @Test
    fun unmatchClosesTheThread() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.unmatch()
        assertEquals(listOf("unmatch:m1"), calls)
        assertTrue(model.closed.value)
    }

    @Test
    fun failedUnmatchStaysWithTheError() = runTest(UnconfinedTestDispatcher()) {
        actionError = ApiError.Http(404, "Match not found")
        val model = model()
        model.unmatch()
        assertFalse(model.closed.value)
        assertEquals("Match not found", model.errorMessage.value)
    }

    @Test
    fun blockUsesTheOtherMemberAndCloses() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.block()
        assertEquals(listOf("block:pema:Pema"), calls)
        assertTrue(model.closed.value)
    }

    @Test
    fun blockAndReportNeedTheEntry() = runTest(UnconfinedTestDispatcher()) {
        entries.value = emptyList()
        val model = model()
        model.block()
        model.report("Spam")
        assertEquals(emptyList<String>(), calls)
        assertFalse(model.closed.value)
    }

    @Test
    fun reportDoesNotClose() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.report("Harassment")
        assertEquals(listOf("report:pema:Harassment"), calls)
        assertFalse(model.closed.value)

        actionError = ApiError.NotAuthenticated
        model.report("Spam")
        assertEquals("You need to sign in again.", model.errorMessage.value)
    }

    @Test
    fun threadActionsFinishAfterTheThreadIsClosed() = runTest(UnconfinedTestDispatcher()) {
        // Back pressed right after choosing: the entry is popped and the model cleared, but the
        // requests (iOS unstructured Tasks) still go through.
        actionGate = CompletableDeferred()
        val model = model()
        model.report("Spam")
        model.block()
        storeOf(model).clear()
        actionGate!!.complete(Unit)
        assertEquals(setOf("report:pema:Spam", "block:pema:Pema"), calls.toSet())
        assertTrue(model.closed.value)
    }

    // endregion

    // region Message docs

    @Test
    fun messageDocMapping() {
        val now = Instant.ofEpochSecond(42)
        val full = ChatMessage.fromFirestore(
            "x",
            mapOf(
                "senderId" to "pema",
                "text" to "🎤 Voice message",
                "imageUrl" to null,
                "audioUrl" to "https://a.m4a",
                // Android's Firestore SDK returns integers as Long.
                "audioDurationSec" to 9L,
                "createdAt" to Timestamp(1_760_000_000L, 0),
                "readAt" to null,
            ),
            now = { now },
        )!!
        assertEquals(
            ChatMessage("x", "pema", "🎤 Voice message", audioUrl = "https://a.m4a", audioDurationSec = 9, createdAt = Instant.ofEpochSecond(1_760_000_000L)),
            full,
        )
        assertTrue(full.hasMedia)

        // A pending server timestamp with no estimate falls back to now.
        val pending = ChatMessage.fromFirestore("y", mapOf("senderId" to me, "text" to "hi", "createdAt" to null), now = { now })!!
        assertEquals(now, pending.createdAt)
        assertFalse(pending.hasMedia)

        assertNull(ChatMessage.fromFirestore("z", mapOf("text" to "no sender")))
        assertNull(ChatMessage.fromFirestore("z", mapOf("senderId" to me)))
        assertNull(ChatMessage.fromFirestore("z", mapOf("senderId" to me, "text" to 5L)))
    }

    // endregion
}
