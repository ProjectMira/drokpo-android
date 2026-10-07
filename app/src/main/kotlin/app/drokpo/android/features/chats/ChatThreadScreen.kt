package app.drokpo.android.features.chats

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.features.shared.audio.AudioBubbleView
import app.drokpo.android.features.shared.sharing.ShareDestination
import app.drokpo.android.features.shared.sharing.SharedLinkMessage
import app.drokpo.android.ui.components.ActionSheet
import app.drokpo.android.ui.components.ActionSheetItem
import app.drokpo.android.ui.components.Avatar
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Port of ChatThreadView: a match's messages with the input bar pinned under them. Pushed by the
 * list, the "New matches" strip, and push deep links (possibly before ChatStore has delivered this
 * match — the entry is then null: title "Chat", no avatar or menu yet).
 */
@Composable
internal fun ChatThreadScreen(
    matchId: String,
    onBack: () -> Unit,
    onOpenProfile: (FeedCard) -> Unit,
) {
    val chats = LocalChatStore.current
    val myUid = AppGraph.session.uid
    val model: ChatThreadModel = viewModel(key = "chat-thread-$matchId") {
        ChatThreadModel(
            matchId = matchId,
            myUid = myUid,
            entries = chats.entries,
            clearUnread = chats::clearUnread,
        )
    }
    val entries by chats.entries.collectAsStateWithLifecycle()
    val entry = entries.firstOrNull { it.matchId == matchId }
    val messages by model.messages.collectAsStateWithLifecycle()
    val errorMessage by model.errorMessage.collectAsStateWithLifecycle()
    val closed by model.closed.collectAsStateWithLifecycle()

    // iOS onAppear/onDisappear: listen only while this thread is on screen (and the app is in the
    // foreground), so incoming messages aren't marked read behind a pushed profile.
    LifecycleStartEffect(model) {
        model.attach()
        onStopOrDispose { model.detach() }
    }
    // Unmatch / block went through → iOS `dismiss()`.
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(closed) {
        if (closed) currentOnBack()
    }

    var dialog by rememberSaveable { mutableStateOf<ThreadDialog?>(null) }
    var viewingImageUrl by rememberSaveable { mutableStateOf<String?>(null) }

    ChatThreadContent(
        state = ChatThreadUiState(entry = entry, messages = messages, myUid = myUid, errorMessage = errorMessage),
        onBack = onBack,
        onOpenProfile = onOpenProfile,
        onOpenImage = { viewingImageUrl = it },
        // A share-sheet message renders as a card; MainTabs presents what it links to.
        onOpenShared = { destination -> AppGraph.deepLinks.pendingShare.value = destination },
        dialog = dialog,
        onDialogChange = { dialog = it },
        onUnmatch = model::unmatch,
        onBlock = model::block,
        onReport = model::report,
        onDismissError = model::dismissError,
        inputBar = { ChatInputBar(model) },
    )

    viewingImageUrl?.let { url ->
        ChatImageViewer(url = url, onDismiss = { viewingImageUrl = null })
    }
}

/** Everything ChatThreadContent renders besides the input bar. */
internal data class ChatThreadUiState(
    /** The live match entry; null until ChatStore has delivered it. */
    val entry: ChatStore.Entry?,
    val messages: List<ChatMessage>,
    val myUid: String?,
    val errorMessage: String? = null,
)

/**
 * Stateless thread: inline title (`otherUser.displayName ?: "Chat"`), a 32dp avatar that opens the
 * profile and the Unmatch / Block / Report menu once the entry is known, the message list (day
 * chips, bubbles, time captions; kept scrolled to the newest message), [inputBar] above the
 * keyboard, and the confirmations.
 */
@Composable
internal fun ChatThreadContent(
    state: ChatThreadUiState,
    onBack: () -> Unit,
    onOpenProfile: (FeedCard) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenShared: (ShareDestination) -> Unit,
    dialog: ThreadDialog?,
    onDialogChange: (ThreadDialog?) -> Unit,
    onUnmatch: () -> Unit,
    onBlock: () -> Unit,
    onReport: (String) -> Unit,
    onDismissError: () -> Unit,
    inputBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Catalog only: render with the toolbar menu open. */
    initialMenuExpanded: Boolean = false,
) {
    val colors = DrokpoTheme.colors
    val other = state.entry?.otherUser
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = other?.displayName ?: "Chat",
                navIcon = NavIcon.Back,
                onNavIcon = onBack,
                actions = {
                    if (other != null) {
                        IconButton(onClick = { onOpenProfile(other) }) {
                            Avatar(photo = other.photos?.firstOrNull(), name = other.displayName, size = 32.dp)
                        }
                    }
                    if (state.entry != null) {
                        ThreadMenu(initiallyExpanded = initialMenuExpanded, onSelect = onDialogChange)
                    }
                },
            )
        },
        containerColor = colors.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            MessageList(
                messages = state.messages,
                myUid = state.myUid,
                listState = listState,
                onOpenImage = onOpenImage,
                onOpenShared = onOpenShared,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            inputBar()
        }
    }

    when (dialog) {
        ThreadDialog.Unmatch -> ActionSheet(
            onDismissRequest = { onDialogChange(null) },
            title = "Unmatch?",
            message = "You'll no longer see each other or be able to message.",
            items = listOf(ActionSheetItem("Unmatch", destructive = true, onClick = onUnmatch)),
        )
        ThreadDialog.Block -> ActionSheet(
            onDismissRequest = { onDialogChange(null) },
            title = "Block this person?",
            message = "They won't be able to message you, and you'll be unmatched.",
            items = listOf(ActionSheetItem("Block", destructive = true, onClick = onBlock)),
        )
        ThreadDialog.Report -> ActionSheet(
            onDismissRequest = { onDialogChange(null) },
            title = "Report this person",
            items = Vocabulary.reportReasons.map { reason -> ActionSheetItem(reason) { onReport(reason) } },
        )
        null -> Unit
    }
    ErrorAlert(state.errorMessage, onDismiss = onDismissError)
}

/** The toolbar `Menu` (`ellipsis.circle` → Outlined.Pending): Unmatch, Block (destructive) and Report. */
@Composable
private fun ThreadMenu(initiallyExpanded: Boolean, onSelect: (ThreadDialog) -> Unit) {
    val colors = DrokpoTheme.colors
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Pending, contentDescription = "More")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = if (colors.isDark) colors.tertiaryBackground else colors.background,
        ) {
            listOf(
                Triple("Unmatch", true, ThreadDialog.Unmatch),
                Triple("Block", true, ThreadDialog.Block),
                Triple("Report", false, ThreadDialog.Report),
            ).forEach { (label, destructive, target) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            style = DrokpoTheme.typography.body,
                            color = if (destructive) colors.destructive else colors.label,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(target)
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    myUid: String?,
    listState: LazyListState,
    onOpenImage: (String) -> Unit,
    onOpenShared: (ShareDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val rows = remember(messages, zone) { threadRows(messages, zone) }
    val timeFormatter = rememberTimeFormatter()

    // Scrolled to the newest message on appear (no animation) and, animated, on every change
    // (iOS `.onAppear` / `.onChange(of: messages)` → `scrollTo(last, anchor: .bottom)`).
    var scrolledOnAppear by remember { mutableStateOf(false) }
    LaunchedEffect(rows) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (scrolledOnAppear) {
            listState.animateScrollToItem(rows.lastIndex)
        } else {
            listState.scrollToItem(rows.lastIndex)
            scrolledOnAppear = true
        }
    }
    // The keyboard (or a growing multi-line draft) shrinks the list from the bottom; keep the newest
    // message in view instead of letting it slide under the input bar.
    val currentRows by rememberUpdatedState(rows)
    LaunchedEffect(listState) {
        var lastHeight = 0
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { height ->
            if (height in 1 until lastHeight && currentRows.isNotEmpty()) {
                listState.scrollToItem(currentRows.lastIndex)
            }
            lastHeight = height
        }
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is ThreadRow.Day -> DaySeparator(row.date, zone)
                is ThreadRow.Bubble -> MessageBubble(
                    message = row.message,
                    isMine = row.message.senderId == myUid,
                    onOpenImage = onOpenImage,
                    onOpenShared = onOpenShared,
                )
                is ThreadRow.Time -> TimestampCaption(
                    text = timeFormatter.format(row.date.atZone(zone)),
                    isMine = row.message.senderId == myUid,
                )
            }
        }
    }
}

/** iOS `date.formatted(date: .omitted, time: .shortened)`: the locale's short time, honouring 24-hour. */
@Composable
private fun rememberTimeFormatter(): DateTimeFormatter {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, is24Hour) {
        val pattern = DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm")
        DateTimeFormatter.ofPattern(pattern, locale)
    }
}

/** "Today" / "Yesterday" / "Oct 5, 2026" in a small capsule, centred. */
@Composable
internal fun DaySeparator(date: Instant, zone: ZoneId, modifier: Modifier = Modifier) {
    val colors = DrokpoTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            daySeparatorText(date, now = Instant.now(), zone = zone),
            style = DrokpoTheme.typography.caption2,
            color = colors.secondaryLabel,
            modifier = Modifier
                .background(colors.fill, CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The short time under the last message of a run, on the sender's side. */
@Composable
private fun TimestampCaption(text: String, isMine: Boolean) {
    Text(
        text,
        style = DrokpoTheme.typography.caption2,
        color = DrokpoTheme.colors.secondaryLabel,
        textAlign = if (isMine) TextAlign.End else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

private val BubbleShape = RoundedCornerShape(18.dp)

/**
 * One message: mine on the right on the accent, theirs on the left on the quaternary fill, with at
 * least 48dp free on the far side. A photo (220dp square, tap → viewer), a voice clip, a shared
 * Drokpo link (tappable card), or text.
 */
@Composable
internal fun MessageBubble(
    message: ChatMessage,
    isMine: Boolean,
    onOpenImage: (String) -> Unit,
    onOpenShared: (ShareDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    val fill = if (isMine) colors.accent else colors.fill
    val onFill = if (isMine) colors.onAccent else colors.label
    val imageUrl = message.imageUrl?.takeIf { it.isNotBlank() }
    val audioUrl = message.audioUrl?.takeIf { it.isNotBlank() }
    val shared = remember(message.text, imageUrl, audioUrl) {
        if (imageUrl == null && audioUrl == null) SharedLinkMessage.from(message.text) else null
    }
    val alignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart

    if (audioUrl != null) {
        // The clip's progress bar is flexible, so on iOS the bubble takes about half the row
        // (sharing the HStack with the 48pt spacer), never narrower than the 140pt player.
        Box(modifier.fillMaxWidth(), contentAlignment = alignment) {
            Box(
                Modifier
                    .widthIn(min = 168.dp)
                    .fillMaxWidth(0.5f)
                    .background(fill, BubbleShape)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                AudioBubbleView(
                    id = message.id,
                    url = audioUrl,
                    durationSec = message.audioDurationSec ?: 0,
                    modifier = Modifier.fillMaxWidth(),
                    isOnTintBackground = isMine,
                )
            }
        }
        return
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = if (isMine) 48.dp else 0.dp, end = if (isMine) 0.dp else 48.dp),
        contentAlignment = alignment,
    ) {
        when {
            imageUrl != null -> Box(
                Modifier
                    .size(220.dp)
                    .clip(BubbleShape)
                    .clickable { onOpenImage(imageUrl) },
            ) {
                RemotePhotoView(
                    storagePath = "chat-image-${message.id}",
                    url = imageUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = "Photo",
                )
            }
            // A share-sheet message ("<title>\n<drokpo share link>") renders as a tappable card
            // that opens the shared content in-app.
            shared != null -> Column(
                Modifier
                    .clip(BubbleShape)
                    .background(fill)
                    .clickable { onOpenShared(shared.destination) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.alpha(0.85f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(shared.icon, contentDescription = null, tint = onFill, modifier = Modifier.size(14.dp))
                    Text("Shared ${shared.kindLabel}", style = typography.caption.bold(), color = onFill)
                }
                shared.caption?.let { caption ->
                    Text(caption, style = typography.body.bold(), color = onFill)
                }
                Text("Tap to view", style = typography.caption2, color = onFill, modifier = Modifier.alpha(0.7f))
            }
            else -> Text(
                message.text,
                style = typography.body,
                color = onFill,
                modifier = Modifier
                    .background(fill, BubbleShape)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
