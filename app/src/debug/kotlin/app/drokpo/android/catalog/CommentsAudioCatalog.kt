package app.drokpo.android.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.CommentCard
import app.drokpo.android.features.shared.audio.AudioBubbleContent
import app.drokpo.android.features.shared.audio.AudioRecorder
import app.drokpo.android.features.shared.audio.RecordedClip
import app.drokpo.android.features.shared.audio.RecorderFailureRowContent
import app.drokpo.android.features.shared.audio.RecorderState
import app.drokpo.android.features.shared.comments.CommentComposerBar
import app.drokpo.android.features.shared.comments.CommentSafetySheets
import app.drokpo.android.features.shared.comments.CommentsActions
import app.drokpo.android.features.shared.comments.CommentsContent
import app.drokpo.android.features.shared.comments.CommentsUiState
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ErrorAlert
import app.drokpo.android.ui.components.GroupedSection
import app.drokpo.android.ui.components.LocalInsideSheet
import app.drokpo.android.ui.theme.DrokpoTheme
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

// Group 10 (comments + audio). Every CommentsSheet state is rendered through the stateless
// CommentsContent inside a sheet-shaped frame (the real sheet is a ModalBottomSheet window);
// the composer and audio pieces render through their stateless bodies. Fixtures only — no
// ViewModel, network, Firebase or AppGraph access (CONTRACT.md §E).
val commentsAudioCatalogEntries: List<CatalogEntry> = listOf(
    // ---------------------------------------------------------------- sheet states
    CatalogEntry("commentsaudio.sheet.loading", "Comments — first load (spinner)") {
        CommentsPreview(memberState(isLoading = true, comments = emptyList()))
    },
    CatalogEntry("commentsaudio.sheet.empty", "Comments — empty") {
        CommentsPreview(memberState(comments = emptyList()))
    },
    CatalogEntry("commentsaudio.sheet.error", "Comments — load failed (alert over empty)") {
        CommentsPreview(memberState(comments = emptyList()))
        ErrorAlert(message = OFFLINE_MESSAGE, onDismiss = {})
    },
    CatalogEntry("commentsaudio.sheet.populated", "Comments — text, voice, community, thread expanded") {
        CommentsPreview(memberState(expanded = true))
    },
    CatalogEntry("commentsaudio.sheet.collapsed", "Comments — threads collapsed (View 2 replies)") {
        CommentsPreview(memberState())
    },
    CatalogEntry("commentsaudio.sheet.repliesloading", "Comments — thread expanding (replies spinner)") {
        CommentsPreview(memberState().copy(expandedParents = setOf(THREAD_ID)))
    },
    CatalogEntry("commentsaudio.sheet.loadingmore", "Comments — paging older comments") {
        CommentsPreview(memberState().copy(isLoadingMore = true))
    },
    CatalogEntry("commentsaudio.sheet.refreshing", "Comments — pull-to-refresh in flight") {
        CommentsPreview(memberState().copy(isRefreshing = true))
    },
    CatalogEntry("commentsaudio.sheet.tombstone", "Comments — deleted-account tombstone + nameless author") {
        CommentsPreview(memberState(comments = listOf(tombstone, nameless) + Fixtures.comments.take(2)))
    },
    CatalogEntry("commentsaudio.sheet.halfheight", "Comments — half-height stop (composer pinned to the visible bottom)") {
        HalfHeightFrame { CommentsBody(memberState(expanded = true)) }
    },
    CatalogEntry("commentsaudio.sheet.halfheight.empty", "Comments — half-height stop, empty (centred in the visible half)") {
        HalfHeightFrame { CommentsBody(memberState(comments = emptyList())) }
    },
    CatalogEntry("commentsaudio.sheet.halfheight.loading", "Comments — half-height stop, first load (spinner in the visible half)") {
        HalfHeightFrame { CommentsBody(memberState(comments = emptyList(), isLoading = true)) }
    },
    // ---------------------------------------------------------------- row actions
    CatalogEntry("commentsaudio.sheet.swipe.others", "Swipe — someone else's comment (Report)") {
        CommentsPreview(memberState(), initialOpenSwipeId = "cm-1")
    },
    CatalogEntry("commentsaudio.sheet.swipe.mine", "Swipe — my comment (Delete)") {
        CommentsPreview(memberState(), initialOpenSwipeId = "cm-5")
    },
    CatalogEntry("commentsaudio.sheet.swipe.owner", "Swipe — post owner on a member's comment (Delete + Report)") {
        CommentsPreview(ownerState(), initialOpenSwipeId = "cm-2")
    },
    CatalogEntry("commentsaudio.sheet.swipe.reply", "Swipe — my community's reply in a thread (Delete)") {
        CommentsPreview(ownerState(expanded = true), initialOpenSwipeId = "cm-1-r1")
    },
    CatalogEntry("commentsaudio.sheet.menu", "Long-press — Report or block…") {
        CommentsPreview(memberState(), initialMenuId = "cm-1")
    },
    CatalogEntry("commentsaudio.sheet.menu.owner", "Long-press — post owner (Report or block… + Delete)") {
        CommentsPreview(ownerState(), initialMenuId = "cm-2")
    },
    CatalogEntry("commentsaudio.sheet.menu.mine", "Long-press — my comment (Delete)") {
        CommentsPreview(memberState(), initialMenuId = "cm-5")
    },
    CatalogEntry("commentsaudio.sheet.safety", "Safety — \"Comment by Karma Tsering\"") {
        CommentsPreview(memberState())
        CommentSafetySheets(
            safetyTarget = Fixtures.comments.first(),
            reportTarget = null,
            onDismissSafety = {},
            onDismissReport = {},
            onReport = {},
            onBlock = {},
            onReportReason = { _, _ -> },
        )
    },
    CatalogEntry("commentsaudio.sheet.safety.member", "Safety — nameless author (\"Comment by member\")") {
        CommentsPreview(memberState(comments = listOf(nameless) + Fixtures.comments))
        CommentSafetySheets(
            safetyTarget = nameless,
            reportTarget = null,
            onDismissSafety = {},
            onDismissReport = {},
            onReport = {},
            onBlock = {},
            onReportReason = { _, _ -> },
        )
    },
    CatalogEntry("commentsaudio.sheet.reportreasons", "Safety — \"Why are you reporting this comment?\"") {
        CommentsPreview(memberState())
        CommentSafetySheets(
            safetyTarget = null,
            reportTarget = Fixtures.comments.first(),
            onDismissSafety = {},
            onDismissReport = {},
            onReport = {},
            onBlock = {},
            onReportReason = { _, _ -> },
        )
    },
    // ---------------------------------------------------------------- composer
    CatalogEntry("commentsaudio.composer.idle", "Composer — idle (placeholder + mic)") {
        CommentsPreview(memberState())
    },
    CatalogEntry("commentsaudio.composer.text", "Composer — typed text (send arrow)") {
        CommentsPreview(memberState(), composer = ComposerPreview(text = "See you on Wednesday!"))
    },
    CatalogEntry("commentsaudio.composer.multiline", "Composer — long text (grows to 4 lines)") {
        CommentsPreview(memberState(), composer = ComposerPreview(text = LONG_DRAFT))
    },
    CatalogEntry("commentsaudio.composer.reply", "Composer — replying to (chip + text)") {
        CommentsPreview(
            memberState(expanded = true).copy(replyTarget = Fixtures.comments.first()),
            composer = ComposerPreview(text = "Thank you!"),
        )
    },
    CatalogEntry("commentsaudio.composer.recording", "Composer — recording (12s / 60s)") {
        CommentsPreview(memberState(), composer = ComposerPreview(recorder = RecorderState.Recording(elapsedSeconds = 12)))
    },
    CatalogEntry("commentsaudio.composer.preview", "Composer — recorded clip preview (bubble + trash + send)") {
        CommentsPreview(memberState(), composer = ComposerPreview(recorded = draftClip))
    },
    CatalogEntry("commentsaudio.composer.sending", "Composer — sending (spinner replaces the arrow)") {
        CommentsPreview(memberState().copy(isSending = true), composer = ComposerPreview(text = "See you on Wednesday!"))
    },
    CatalogEntry("commentsaudio.composer.sending.audio", "Composer — sending a voice comment") {
        CommentsPreview(memberState().copy(isSending = true), composer = ComposerPreview(recorded = draftClip))
    },
    CatalogEntry("commentsaudio.composer.failed.micoff", "Composer — mic denied (Settings link + dismiss)") {
        CommentsPreview(
            memberState(),
            composer = ComposerPreview(recorder = RecorderState.Failed(MIC_OFF), showSettingsLink = true),
        )
    },
    CatalogEntry("commentsaudio.composer.failed.start", "Composer — recorder failed to start") {
        CommentsPreview(memberState(), composer = ComposerPreview(recorder = RecorderState.Failed(START_FAILED)))
    },
    // ---------------------------------------------------------------- audio components
    CatalogEntry("commentsaudio.audio.bubbles", "AudioBubbleView — idle / playing / on tint") {
        AudioGallery()
    },
    CatalogEntry("commentsaudio.audio.recorderfailure", "RecorderFailureRow — with and without the Settings link") {
        RecorderFailureGallery()
    },
)

// ---------------------------------------------------------------- fixtures

private const val THREAD_ID = "cm-1"
private const val OFFLINE_MESSAGE = "The Internet connection appears to be offline."
private const val LONG_DRAFT =
    "Can we also bring younger siblings to the Saturday class? My brother is six and already knows " +
        "a few letters. He'd love to sit with the older kids if that's okay with the teachers."

/** The AudioRecorder failure copy, shown verbatim. */
private val MIC_OFF = AudioRecorder.MIC_OFF_MESSAGE
private val START_FAILED = AudioRecorder.START_FAILED_MESSAGE

/** The post every comment hangs off — owned by TAT. */
private val postOwnerCid: String? = Fixtures.announcementPost.communityId

private fun isoDaysAgo(days: Long): String =
    Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()

/** What account deletion leaves behind (backend COMMENT_TOMBSTONE). */
private val tombstone = CommentCard(
    commentId = "cm-tombstone",
    authorName = "Deleted account",
    text = "This comment was deleted.",
    replyCount = 0,
    createdAt = isoDaysAgo(3),
)

/** An author with no name or photo snapshot — "—" in the row, "member" in the safety sheet. */
private val nameless = CommentCard(
    commentId = "cm-nameless",
    authorUid = "u-anon",
    authorKind = "person",
    text = "Is there parking near the centre?",
    likeCount = 1,
    createdAt = isoDaysAgo(2),
)

private val draftClip = RecordedClip(File("catalog-draft.m4a"), seconds = 9)

/** Viewer = an ordinary member (Fixtures.MY_UID); cm-5 is theirs. */
private fun memberState(
    comments: List<CommentCard> = Fixtures.comments,
    expanded: Boolean = false,
    isLoading: Boolean = false,
): CommentsUiState = CommentsUiState(
    myUid = Fixtures.MY_UID,
    postOwnerCid = postOwnerCid,
    comments = comments,
    repliesByParent = if (expanded) mapOf(THREAD_ID to Fixtures.replies) else emptyMap(),
    expandedParents = if (expanded) setOf(THREAD_ID) else emptySet(),
    isLoading = isLoading,
    hasMore = true,
)

/** Viewer = the community that owns the post (may delete any comment on it). */
private fun ownerState(expanded: Boolean = false): CommentsUiState =
    memberState(expanded = expanded).copy(myUid = postOwnerCid)

private data class ComposerPreview(
    val text: String = "",
    val recorder: RecorderState = RecorderState.Idle,
    val recorded: RecordedClip? = null,
    /** Whether a failure row shows the Settings link (mic permission denied). */
    val showSettingsLink: Boolean = false,
)

// ---------------------------------------------------------------- frames

@Composable
private fun CommentsPreview(
    state: CommentsUiState,
    initialOpenSwipeId: String? = null,
    initialMenuId: String? = null,
    composer: ComposerPreview = ComposerPreview(),
) {
    SheetFrame { CommentsBody(state, initialOpenSwipeId, initialMenuId, composer) }
}

@Composable
private fun CommentsBody(
    state: CommentsUiState,
    initialOpenSwipeId: String? = null,
    initialMenuId: String? = null,
    composer: ComposerPreview = ComposerPreview(),
) {
    CommentsContent(
        state = state,
        actions = CommentsActions(),
        onClose = {},
        modifier = Modifier.fillMaxSize(),
        initialOpenSwipeId = initialOpenSwipeId,
        initialMenuId = initialMenuId,
    ) { modifier ->
        ComposerPreviewBar(
            preview = composer,
            replyingTo = state.replyTarget?.let { it.authorName ?: "member" },
            isSending = state.isSending,
            modifier = modifier,
        )
    }
}

@Composable
private fun ComposerPreviewBar(preview: ComposerPreview, replyingTo: String?, isSending: Boolean, modifier: Modifier) {
    var text by remember { mutableStateOf(preview.text) }
    CommentComposerBar(
        replyingTo = replyingTo,
        text = text,
        onTextChange = { text = it },
        recorderState = preview.recorder,
        recorded = preview.recorded,
        isSending = isSending,
        onCancelReply = {},
        onStartRecording = {},
        onCancelRecording = {},
        onStopRecording = {},
        onDiscardRecording = {},
        onDismissFailure = {},
        onSend = {},
        modifier = modifier,
        // The live failure row checks this device's real mic permission; pin the variant instead.
        settingsLinkOverride = preview.showSettingsLink,
    )
}

/** A full-height sheet: dimmed backdrop, rounded top, grabber (the [.medium, .large] sheet's handle). */
@Composable
private fun SheetFrame(content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(DrokpoTheme.colors.dimmingScrim)
            .statusBarsPadding()
            .padding(top = 8.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(DrokpoTheme.colors.background),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BottomSheetDefaults.DragHandle()
            CompositionLocalProvider(LocalInsideSheet provides true) {
                content()
            }
        }
    }
}

/**
 * The sheet at its half-height stop: as tall as the screen but pushed half off
 * the bottom edge, exactly how a partially expanded ModalBottomSheet sits —
 * the composer must still show above the navigation bar.
 */
@Composable
private fun HalfHeightFrame(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(DrokpoTheme.colors.dimmingScrim),
    ) {
        val height = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .requiredHeight(height)
                .offset(y = height / 2)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(DrokpoTheme.colors.background),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BottomSheetDefaults.DragHandle()
            CompositionLocalProvider(LocalInsideSheet provides true) {
                content()
            }
        }
    }
}

// ---------------------------------------------------------------- audio galleries

@Composable
private fun AudioGallery() {
    val colors = DrokpoTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        DrokpoTopBar("Voice clips", containerColor = colors.groupedBackground)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GroupedSection(header = "Comment row") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AudioBubbleContent(isPlaying = false, progress = { 0f }, durationSec = 14, onToggle = {})
                    AudioBubbleContent(isPlaying = true, progress = { 0.4f }, durationSec = 14, onToggle = {})
                    AudioBubbleContent(isPlaying = true, progress = { 0f }, durationSec = 75, onToggle = {})
                }
            }
            GroupedSection(header = "Chat bubble — mine (on tint)") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
                    TintBubble { AudioBubbleContent(isPlaying = false, progress = { 0f }, durationSec = 6, onToggle = {}, isOnTintBackground = true) }
                    TintBubble { AudioBubbleContent(isPlaying = true, progress = { 0.65f }, durationSec = 42, onToggle = {}, isOnTintBackground = true) }
                }
            }
            GroupedSection(header = "Chat bubble — theirs") {
                Column(Modifier.padding(16.dp)) {
                    Box(
                        Modifier
                            .widthIn(max = 260.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.fill)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        AudioBubbleContent(isPlaying = false, progress = { 0f }, durationSec = 125, onToggle = {})
                    }
                }
            }
        }
    }
}

@Composable
private fun TintBubble(content: @Composable () -> Unit) {
    Box(
        Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(DrokpoTheme.colors.accent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        content()
    }
}

@Composable
private fun RecorderFailureGallery() {
    val colors = DrokpoTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        DrokpoTopBar("Recorder failures", containerColor = colors.groupedBackground)
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GroupedSection(header = "Mic permission denied", footer = "Settings opens this app's system settings page.") {
                RecorderFailureRowContent(
                    message = MIC_OFF,
                    showSettingsLink = true,
                    onOpenSettings = {},
                    onDismiss = {},
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                )
            }
            GroupedSection(header = "Recorder setup failed") {
                RecorderFailureRowContent(
                    message = AudioRecorder.MIC_UNAVAILABLE_MESSAGE,
                    showSettingsLink = false,
                    onOpenSettings = {},
                    onDismiss = {},
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                )
                RecorderFailureRowContent(
                    message = START_FAILED,
                    showSettingsLink = false,
                    onOpenSettings = {},
                    onDismiss = {},
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                )
            }
            Text(
                "The X returns the composer to its text field, so a denied mic never blocks typing.",
                style = DrokpoTheme.typography.footnote,
                color = colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}
