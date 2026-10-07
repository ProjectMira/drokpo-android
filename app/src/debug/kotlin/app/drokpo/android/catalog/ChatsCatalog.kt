package app.drokpo.android.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.MainTab
import app.drokpo.android.MainTabsScaffold
import app.drokpo.android.features.chats.ChatInputBarContent
import app.drokpo.android.features.chats.ChatImageViewerContent
import app.drokpo.android.features.chats.ChatMessage
import app.drokpo.android.features.chats.ChatStore
import app.drokpo.android.features.chats.ChatThreadContent
import app.drokpo.android.features.chats.ChatThreadModel
import app.drokpo.android.features.chats.ChatThreadUiState
import app.drokpo.android.features.chats.ChatsContent
import app.drokpo.android.features.chats.ChatsListState
import app.drokpo.android.features.chats.MessageBubble
import app.drokpo.android.features.chats.PHOTO_LOAD_FAILED
import app.drokpo.android.features.chats.ThreadDialog
import app.drokpo.android.features.shared.audio.RecordedClip
import app.drokpo.android.features.shared.audio.RecorderState
import app.drokpo.android.features.shared.sharing.ShareableContent
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.visibleTabs
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

// Group 6 (chats): the Chats list, a thread (every bubble kind, separators, menu and
// confirmations), the input bar's states and the photo viewer — all from fixtures (CONTRACT §E).

private val me = Fixtures.MY_UID
private val pema = Fixtures.chatEntries.first { it.matchId == "m-pema" }
private val yangchen = Fixtures.chatEntries.first { it.matchId == "m-yangchen" }
private val community = Fixtures.chatEntries.first { it.matchId == "m-tat" }

private fun minutesAgo(minutes: Long): Instant = Instant.now().minus(minutes, ChronoUnit.MINUTES)

private val listState = ChatsListState(
    isLoading = false,
    newMatches = Fixtures.chatEntries.filter { !it.hasMessages },
    conversations = Fixtures.chatEntries.filter { it.hasMessages },
    myUid = me,
)

/** Thread with the community account: plain text both ways. */
private val communityMessages: List<ChatMessage> = listOf(
    ChatMessage("c-1", me, "Hi! Is the Saturday event open to newcomers?", createdAt = minutesAgo(3 * 24 * 60L + 40)),
    ChatMessage(
        "c-2",
        community.otherUid,
        "Absolutely — everyone is welcome. Bring a friend if you like!",
        createdAt = minutesAgo(3 * 24 * 60L + 20),
    ),
    ChatMessage("c-3", me, "Thank you for the warm welcome!", createdAt = minutesAgo(3 * 24 * 60L + 18)),
    ChatMessage(
        "c-4",
        community.otherUid,
        "Thanks for joining — our next event is on Saturday.",
        createdAt = minutesAgo(2 * 24 * 60L),
    ),
)

/** A long thread over four days: runs, >10 min gaps, and a long paragraph. */
private val longMessages: List<ChatMessage> = buildList {
    val lines = listOf(
        "Tashi delek!", "How was your weekend?", "Went hiking near Triund 🏔️", "No way, I love that trail",
        "We should go together next time", "Definitely!", "Have you tried the momos at the new place?",
        "Not yet — are they good?", "The best in town, honestly",
        "Okay so here is a longer message to check how a paragraph wraps inside a bubble: the " +
            "festival starts at noon, the dance troupe performs at three, and dinner is at the " +
            "community hall around seven. Let me know if you can make it!",
        "I'll be there", "See you soon 🙂",
    )
    var minutes = 4 * 24 * 60L
    lines.forEachIndexed { index, text ->
        val sender = if (index % 3 == 1) me else pema.otherUid
        add(ChatMessage("long-$index", sender, text, createdAt = minutesAgo(minutes)))
        minutes -= if (index % 4 == 3) 26 * 60L else if (index % 2 == 0) 1 else 14
    }
}

/** One of every bubble kind, from both sides. */
private val bubbleGallery: List<ChatMessage> = listOf(
    ChatMessage("g-1", pema.otherUid, "Text from them", createdAt = minutesAgo(30)),
    ChatMessage("g-2", me, "Text from me", createdAt = minutesAgo(29)),
    ChatMessage("g-3", pema.otherUid, "📷 Photo", imageUrl = Fixtures.imageUrl("drokpo-chat-bubble"), createdAt = minutesAgo(28)),
    ChatMessage("g-4", pema.otherUid, "🎤 Voice message", audioUrl = "https://example.org/voice.m4a", audioDurationSec = 75, createdAt = minutesAgo(27)),
    ChatMessage("g-5", me, "🎤 Voice message", audioUrl = "https://example.org/voice-2.m4a", audioDurationSec = 4, createdAt = minutesAgo(26)),
    ChatMessage("g-6", pema.otherUid, ShareableContent.Profile(Fixtures.feedCard).messageText, createdAt = minutesAgo(25)),
    ChatMessage("g-7", me, ShareableContent.Community(Fixtures.community.uid ?: "c-fixture", Fixtures.community.name).messageText, createdAt = minutesAgo(24)),
    ChatMessage("g-8", pema.otherUid, ShareableContent.Post(Fixtures.eventPost).messageText, createdAt = minutesAgo(23)),
    ChatMessage("g-9", me, ShareableContent.News(Fixtures.news).messageText, createdAt = minutesAgo(22)),
    // A bare link with no caption line.
    ChatMessage("g-10", pema.otherUid, "drokpo://s/user/${Fixtures.feedCard.uid}", createdAt = minutesAgo(21)),
)

private val draftClip = RecordedClip(File("/data/local/tmp/draft-voice.m4a"), seconds = 14)

/** The input bar in one of its states (no recorder, picker or network). */
@Composable
private fun InputBar(
    text: String = "",
    recorderState: RecorderState = RecorderState.Idle,
    recordedClip: RecordedClip? = null,
    isSending: Boolean = false,
) {
    var draft by remember { mutableStateOf(text) }
    ChatInputBarContent(
        text = draft,
        onTextChange = { draft = it },
        recorderState = recorderState,
        recordedClip = recordedClip,
        isSending = isSending,
        onPickPhoto = {},
        onStartRecording = {},
        onCancelRecording = {},
        onStopRecording = {},
        onDiscardClip = {},
        onDismissFailure = {},
        onSend = {},
    )
}

@Composable
private fun ThreadPreview(
    entry: ChatStore.Entry? = pema,
    messages: List<ChatMessage> = Fixtures.chatMessages,
    errorMessage: String? = null,
    dialog: ThreadDialog? = null,
    menuOpen: Boolean = false,
    inputBar: @Composable () -> Unit = { InputBar() },
) {
    var currentDialog by remember { mutableStateOf(dialog) }
    var error by remember { mutableStateOf(errorMessage) }
    ChatThreadContent(
        state = ChatThreadUiState(entry = entry, messages = messages, myUid = me, errorMessage = error),
        onBack = {},
        onOpenProfile = {},
        onOpenImage = {},
        onOpenShared = {},
        dialog = currentDialog,
        onDialogChange = { currentDialog = it },
        onUnmatch = {},
        onBlock = {},
        onReport = {},
        onDismissError = { error = null },
        inputBar = inputBar,
        initialMenuExpanded = menuOpen,
    )
}

@Composable
private fun ChatsList(state: ChatsListState = listState, revealedMatchId: String? = null) {
    var current by remember { mutableStateOf(state) }
    ChatsContent(
        state = current,
        onOpenThread = {},
        // The swiped row animates away, as after a successful unmatch.
        onUnmatch = { entry ->
            current = current.copy(unmatching = current.unmatching + entry.matchId)
            true
        },
        onDismissError = { current = current.copy(errorMessage = null) },
        revealedMatchId = revealedMatchId,
    )
}

/** The Chats tab inside the real tab chrome (tab bar + insets). */
@Composable
private fun InTabs(content: @Composable () -> Unit) {
    MainTabsScaffold(
        tabs = visibleTabs(isCommunity = false),
        selected = MainTab.Chats,
        chatsUnread = Fixtures.chatEntries.sumOf { it.unread },
        onSelect = {},
    ) { content() }
}

@Composable
private fun BubbleGallery() {
    Scaffold(
        topBar = { DrokpoTopBar(title = "Bubbles", navIcon = NavIcon.Back) },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(bubbleGallery, key = { it.id }) { message ->
                MessageBubble(message, isMine = message.senderId == me, onOpenImage = {}, onOpenShared = {})
            }
        }
    }
}

@Composable
private fun InputBarStates() {
    Column(
        Modifier
            .fillMaxSize()
            .background(DrokpoTheme.colors.background),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        InputBar()
        InputBar(text = "Hey! Are you going to the Losar party?")
        InputBar(recorderState = RecorderState.Recording(elapsedSeconds = 37))
        InputBar(recordedClip = draftClip)
        InputBar(recorderState = RecorderState.Failed("Microphone access is off. You can turn it on in Settings."))
        InputBar(text = "Sending this one…", isSending = true)
    }
}

val chatsCatalogEntries: List<CatalogEntry> = listOf(
    // Chats list
    CatalogEntry("chats.list.populated", "Chats — new matches + conversations") { ChatsList() },
    CatalogEntry("chats.list.conversations", "Chats — conversations only") {
        ChatsList(listState.copy(newMatches = emptyList()))
    },
    CatalogEntry("chats.list.newmatches", "Chats — new matches only") {
        ChatsList(listState.copy(conversations = emptyList()))
    },
    CatalogEntry("chats.list.noprofiles", "Chats — profiles not joined yet (\"—\")") {
        ChatsList(
            listState.copy(
                newMatches = listState.newMatches.map { it.copy(otherUser = null) },
                conversations = listState.conversations.map { it.copy(otherUser = null) },
            ),
        )
    },
    CatalogEntry("chats.list.swipe", "Chats — swipe action revealed (Unmatch)") {
        ChatsList(revealedMatchId = "m-karma")
    },
    CatalogEntry("chats.list.loading", "Chats — loading") {
        ChatsList(ChatsListState(isLoading = true, newMatches = emptyList(), conversations = emptyList(), myUid = me))
    },
    CatalogEntry("chats.list.empty", "Chats — empty") {
        ChatsList(ChatsListState(isLoading = false, newMatches = emptyList(), conversations = emptyList(), myUid = me))
    },
    CatalogEntry("chats.list.error", "Chats — listener error alert") {
        ChatsList(
            ChatsListState(
                isLoading = false,
                newMatches = emptyList(),
                conversations = emptyList(),
                myUid = me,
                errorMessage = "The Internet connection appears to be offline.",
            ),
        )
    },
    CatalogEntry("chats.list.unmatch.error", "Chats — unmatch failed alert over the list") {
        ChatsList(listState.copy(errorMessage = "Match not found"))
    },
    CatalogEntry("chats.list.intabs", "Chats — inside the tab bar (badge)") { InTabs { ChatsList() } },

    // Thread
    CatalogEntry("chats.thread.populated", "Thread — text, photo, voice, shared link, two days") { ThreadPreview() },
    CatalogEntry("chats.thread.long", "Thread — long, scrolled to the newest message") {
        ThreadPreview(messages = longMessages)
    },
    CatalogEntry("chats.thread.newmatch", "Thread — new match, no messages yet") {
        ThreadPreview(entry = yangchen, messages = emptyList())
    },
    CatalogEntry("chats.thread.loading", "Thread — opened from a push before the match loaded") {
        ThreadPreview(entry = null, messages = emptyList())
    },
    CatalogEntry("chats.thread.community", "Thread — with a community account") {
        ThreadPreview(entry = community, messages = communityMessages)
    },
    CatalogEntry("chats.thread.nophoto", "Thread — other member without photos (initials)") {
        ThreadPreview(
            entry = pema.copy(otherUser = Fixtures.feedCardMinimal),
            messages = Fixtures.chatMessages.takeLast(3),
        )
    },
    CatalogEntry("chats.thread.noprofile", "Thread — profile not joined yet (\"Chat\", menu only)") {
        ThreadPreview(entry = pema.copy(otherUser = null), messages = Fixtures.chatMessages.takeLast(3))
    },
    CatalogEntry("chats.thread.menu", "Thread — toolbar menu open") { ThreadPreview(menuOpen = true) },
    CatalogEntry("chats.thread.confirm.unmatch", "Thread — \"Unmatch?\" confirmation") {
        ThreadPreview(dialog = ThreadDialog.Unmatch)
    },
    CatalogEntry("chats.thread.confirm.block", "Thread — \"Block this person?\" confirmation") {
        ThreadPreview(dialog = ThreadDialog.Block)
    },
    CatalogEntry("chats.thread.report", "Thread — \"Report this person\" reasons") {
        ThreadPreview(dialog = ThreadDialog.Report)
    },
    CatalogEntry("chats.thread.error", "Thread — send failed alert") {
        ThreadPreview(errorMessage = "Missing or insufficient permissions.")
    },
    CatalogEntry("chats.thread.photoerror", "Thread — picked photo couldn't be loaded") {
        ThreadPreview(errorMessage = PHOTO_LOAD_FAILED)
    },
    CatalogEntry("chats.thread.intabs", "Thread — inside the tab bar (input above it)") { InTabs { ThreadPreview() } },
    CatalogEntry("chats.bubbles.all", "Bubbles — every kind, both sides") { BubbleGallery() },
    CatalogEntry("chats.viewer", "Photo viewer (full screen)") {
        Box(Modifier.fillMaxSize()) {
            ChatImageViewerContent(url = Fixtures.imageUrl("drokpo-chat-thangka"), onDismiss = {})
        }
    },

    // Input bar
    CatalogEntry("chats.input.states", "Input bar — all states") { InputBarStates() },
    CatalogEntry("chats.input.typing", "Input bar — typing (send arrow)") {
        ThreadPreview(inputBar = { InputBar(text = "Hey! Are you going to the Losar party?") })
    },
    CatalogEntry("chats.input.multiline", "Input bar — four lines (capped)") {
        ThreadPreview(
            inputBar = {
                InputBar(text = "Line one of a long message\nline two\nline three\nline four\nline five scrolls")
            },
        )
    },
    CatalogEntry("chats.input.recording", "Input bar — recording (37s / 120s)") {
        ThreadPreview(inputBar = { InputBar(recorderState = RecorderState.Recording(elapsedSeconds = 37)) })
    },
    CatalogEntry("chats.input.recording.max", "Input bar — recording at the cap") {
        ThreadPreview(inputBar = { InputBar(recorderState = RecorderState.Recording(ChatThreadModel.MAX_VOICE_SECONDS)) })
    },
    CatalogEntry("chats.input.preview", "Input bar — recorded clip preview") {
        ThreadPreview(inputBar = { InputBar(recordedClip = draftClip) })
    },
    CatalogEntry("chats.input.micdenied", "Input bar — microphone permission denied") {
        ThreadPreview(
            inputBar = {
                InputBar(recorderState = RecorderState.Failed("Microphone access is off. You can turn it on in Settings."))
            },
        )
    },
    CatalogEntry("chats.input.sending", "Input bar — sending (spinner)") {
        ThreadPreview(inputBar = { InputBar(text = "On my way!", isSending = true) })
    },
)
