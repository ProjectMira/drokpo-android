package app.drokpo.android.features.shared.sharing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.userMessage
import app.drokpo.android.features.chats.ChatStore
import app.drokpo.android.features.chats.LocalChatStore
import app.drokpo.android.ui.components.ControlSize
import app.drokpo.android.ui.components.DrokpoSheet
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.GroupedDefaults
import app.drokpo.android.ui.components.GroupedRow
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.LocalSheetDismiss
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.ProminentButton
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Port of ShareSheetView: share a profile/community/post/news card — send it
 * to a match in-app (drops a tappable link card into that chat) or hand the
 * hosted link to the system share sheet (WhatsApp, Messages, …).
 *
 * iOS `.presentationDetents([.medium, .large])` → a half-height-first sheet
 * whose grabber sits on the List's grouped background, like iOS.
 * Must live under MainTabs (reads [LocalChatStore]). (CONTRACT §B.11, §F.11.)
 */
@Composable
fun ShareSheet(content: ShareableContent, onDismissRequest: () -> Unit) {
    ShareModalSheet(
        onDismissRequest = onDismissRequest,
        skipPartiallyExpanded = false,
        containerColor = DrokpoTheme.colors.groupedBackground,
    ) { close ->
        ShareSheetScreen(content = content, onClose = close)
    }
}

/**
 * A [DrokpoSheet] whose [content] gets the sheet's animated close
 * ([LocalSheetDismiss]): the in-sheet Close (X), and a close that follows a
 * built-in block, slide the sheet down first, as iOS `dismiss()` does, and
 * then call [onDismissRequest]. [containerColor] lets the share sheet's
 * grabber strip be the grouped background instead of a white band above a
 * grey list (light mode).
 */
@Composable
internal fun ShareModalSheet(
    onDismissRequest: () -> Unit,
    skipPartiallyExpanded: Boolean,
    containerColor: Color = DrokpoTheme.colors.background,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    DrokpoSheet(
        onDismissRequest = onDismissRequest,
        skipPartiallyExpanded = skipPartiallyExpanded,
        containerColor = containerColor,
    ) {
        content(LocalSheetDismiss.current ?: onDismissRequest)
    }
}

/** Wires [ShareModel] (presentation scope — fresh per presentation, like iOS `@State`) and the chat list. */
@Composable
internal fun ShareSheetScreen(content: ShareableContent, onClose: () -> Unit) {
    val chats = LocalChatStore.current
    val entries by chats.entries.collectAsStateWithLifecycle()
    val model: ShareModel = viewModel { ShareModel() }
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ShareSheetContent(
        entries = entries,
        state = state,
        onClose = onClose,
        onSend = { entry -> model.send(content.messageText, entry.matchId) },
        onShareOutside = { context.startSystemShare(content) },
        onDismissError = model::dismissError,
    )
}

/** iOS `@State sentMatchIds / sendingMatchIds / errorMessage`. */
internal data class ShareSheetState(
    val sentMatchIds: Set<String> = emptySet(),
    val sendingMatchIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
)

/**
 * Sends [ShareableContent.messageText] into a match's chat (iOS ShareSheetView.send(to:)).
 * Dependencies are constructor parameters with production defaults so JVM tests can fake them.
 */
internal class ShareModel(
    private val currentUid: () -> String? = { AppGraph.session.uid },
    private val sendText: suspend (text: String, matchId: String, senderId: String) -> Unit =
        { text, matchId, senderId -> ChatMessageSender.sendText(text, matchId, senderId) },
) : ViewModel() {
    private val _state = MutableStateFlow(ShareSheetState())
    val state: StateFlow<ShareSheetState> = _state.asStateFlow()

    fun send(text: String, matchId: String) {
        val uid = currentUid() ?: return
        // iOS disables the button while sending and replaces it with "Sent"
        // afterwards; guard here too so a double tap can't post the link twice.
        val current = _state.value
        if (matchId in current.sendingMatchIds || matchId in current.sentMatchIds) return
        _state.update { it.copy(sendingMatchIds = it.sendingMatchIds + matchId) }
        viewModelScope.launch {
            try {
                sendText(text, matchId, uid)
                _state.update { it.copy(sentMatchIds = it.sentMatchIds + matchId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            } finally {
                // iOS `defer { sendingMatchIds.remove(entry.matchId) }`.
                _state.update { it.copy(sendingMatchIds = it.sendingMatchIds - matchId) }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}

/**
 * iOS `ShareLink(item: webURL, subject: title, message: title)` → the system
 * chooser with `text/plain`: the chat message text (caption line + link) and
 * the title as subject (CONTRACT §F.11).
 */
internal fun systemShareIntent(content: ShareableContent): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, content.messageText)
        putExtra(Intent.EXTRA_SUBJECT, content.title)
    }
    return Intent.createChooser(send, null)
}

private fun Context.startSystemShare(content: ShareableContent) {
    try {
        startActivity(systemShareIntent(content))
    } catch (_: ActivityNotFoundException) {
        // No share target at all (stripped-down device images) — nothing to offer, like a
        // ShareLink with no activities.
    }
}

/** Stateless ShareSheetView body: "Send in a chat" + "Outside Drokpo" sections under a "Share" bar. */
@Composable
internal fun ShareSheetContent(
    entries: List<ChatStore.Entry>,
    state: ShareSheetState,
    onClose: () -> Unit,
    onSend: (ChatStore.Entry) -> Unit,
    onShareOutside: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Scaffold(
        modifier = modifier,
        topBar = {
            DrokpoTopBar(
                title = "Share",
                navIcon = NavIcon.Close,
                onNavIcon = onClose,
                containerColor = colors.groupedBackground,
            )
        },
        containerColor = colors.groupedBackground,
    ) { padding ->
        // One lazy item per match (iOS `List { ForEach }` is lazy too), keyed by matchId so a
        // row keeps its photo and button state while ChatStore re-sorts on a new message. The
        // section card is drawn per row ([GroupedCardRow]) because GroupedSection is one item.
        // Firestore match ids are unique; distinctBy only keeps a duplicate from crashing the list.
        val matches = remember(entries) { entries.distinctBy { it.matchId } }
        LazyColumn(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxSize()
                .background(colors.groupedBackground),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp + padding.calculateBottomPadding()),
        ) {
            item(key = "chats-header", contentType = "header") {
                GroupedHeader("Send in a chat")
            }
            if (matches.isEmpty()) {
                item(key = "chats-empty") {
                    GroupedSection {
                        GroupedRow {
                            Text(
                                "Match with someone first to share inside Drokpo.",
                                style = typography.subheadline,
                                color = colors.secondaryLabel,
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(matches, key = { _, entry -> entry.matchId }, contentType = { _, _ -> "match" }) { index, entry ->
                    GroupedCardRow(
                        isFirst = index == 0,
                        isLast = index == matches.lastIndex,
                        // Hairlines start at the name, past the 44dp photo, like iOS list rows.
                        separatorInset = 16.dp + MatchPhotoSize + 12.dp,
                    ) {
                        MatchRow(
                            entry = entry,
                            sent = entry.matchId in state.sentMatchIds,
                            sending = entry.matchId in state.sendingMatchIds,
                            onSend = { onSend(entry) },
                        )
                    }
                }
            }
            item(key = "outside", contentType = "section") {
                GroupedSection(
                    modifier = Modifier.padding(top = GroupedDefaults.SectionSpacing),
                    header = "Outside Drokpo",
                ) {
                    GroupedRow(
                        onClick = onShareOutside,
                        leading = {
                            Icon(Icons.Filled.Share, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
                        },
                    ) {
                        Text("Share via WhatsApp, Messages…", color = colors.accent)
                    }
                }
            }
        }
    }
    ErrorAlert(message = state.errorMessage, onDismiss = onDismissError)
}

private val MatchPhotoSize = 44.dp

/** GroupedSection's header (uppercased footnote, secondary) as its own lazy item. */
@Composable
private fun GroupedHeader(text: String) {
    Text(
        text.uppercase(),
        style = DrokpoTheme.typography.footnote,
        color = DrokpoTheme.colors.secondaryLabel,
        modifier = Modifier.padding(
            start = GroupedDefaults.SectionMargin + 16.dp,
            end = GroupedDefaults.SectionMargin + 16.dp,
            bottom = 7.dp,
        ),
    )
}

/**
 * One row of an inset-grouped section card, drawn per lazy item: rounded top
 * corners on the first row, rounded bottom corners on the last, and a hairline
 * (inset by [separatorInset]) above every row but the first. This matches
 * GroupedSection, which separates its rows the same way.
 */
@Composable
private fun GroupedCardRow(
    isFirst: Boolean,
    isLast: Boolean,
    separatorInset: Dp,
    content: @Composable () -> Unit,
) {
    val colors = DrokpoTheme.colors
    val radius = GroupedDefaults.SectionCornerRadius
    val top = if (isFirst) radius else 0.dp
    val bottom = if (isLast) radius else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    val layoutDirection = LocalLayoutDirection.current
    val separatorColor = colors.separator
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GroupedDefaults.SectionMargin)
            .clip(shape)
            .background(colors.secondaryGroupedBackground)
            .drawWithContent {
                drawContent()
                if (!isFirst) {
                    val stroke = 1f // one physical pixel, like UIKit's hairline
                    val inset = separatorInset.toPx()
                    val (startX, endX) = if (layoutDirection == LayoutDirection.Ltr) {
                        inset to size.width
                    } else {
                        0f to size.width - inset
                    }
                    drawLine(separatorColor, Offset(startX, stroke / 2f), Offset(endX, stroke / 2f), strokeWidth = stroke)
                }
            },
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colors.label,
            LocalTextStyle provides DrokpoTheme.typography.body,
        ) {
            content()
        }
    }
}

@Composable
private fun MatchRow(entry: ChatStore.Entry, sent: Boolean, sending: Boolean, onSend: () -> Unit) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    GroupedRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        leading = {
            RemotePhotoView(
                photo = entry.otherUser?.photos?.firstOrNull(),
                modifier = Modifier
                    .size(MatchPhotoSize)
                    .clip(CircleShape),
            )
        },
        trailing = {
            if (sent) {
                // iOS Label("Sent", systemImage: "checkmark") — subheadline bold, green.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.green, modifier = Modifier.size(16.dp))
                    Text("Sent", style = typography.subheadline.bold(), color = colors.green)
                }
            } else {
                ProminentButton(
                    text = "Send",
                    onClick = onSend,
                    size = ControlSize.Small,
                    enabled = !sending,
                )
            }
        },
    ) {
        Text(
            entry.otherUser?.displayName ?: "—",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
