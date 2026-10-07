package app.drokpo.android.features.shared.comments

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.drokpo.android.features.shared.audio.AudioBubbleView
import app.drokpo.android.features.shared.audio.AudioPlaybackCenter
import app.drokpo.android.features.shared.audio.RecordedClip
import app.drokpo.android.features.shared.audio.RecorderFailureRow
import app.drokpo.android.features.shared.audio.RecorderFailureRowContent
import app.drokpo.android.features.shared.audio.RecorderState
import app.drokpo.android.features.shared.audio.rememberAudioRecorder
import app.drokpo.android.ui.components.Spinner
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.monospacedDigit

/** Voice comments are capped at 60 s (backend MAX_COMMENT_AUDIO_SEC). */
internal const val COMMENT_MAX_AUDIO_SECONDS = 60

/** "12s / 60s" while recording. */
internal fun recordingLabel(elapsedSeconds: Int): String = "${elapsedSeconds}s / ${COMMENT_MAX_AUDIO_SECONDS}s"

/** The send arrow shows only when there's something to send (text that isn't just whitespace, or a clip). */
internal fun composerCanSend(text: String, recorded: RecordedClip?): Boolean = recorded != null || text.isNotBlank()

/** What the composer submits: the recorded clip wins over typed text, which is trimmed. */
internal fun composerDraft(text: String, recorded: RecordedClip?): CommentDraft =
    recorded?.let { CommentDraft.Audio(it.file, it.seconds) } ?: CommentDraft.Text(text.trim())

private fun draftAudioId(clip: RecordedClip): String = "draft-${clip.file.name}"

/**
 * Text or voice, idle/recording/preview — pinned under the comments list. Owns
 * the draft (text, recorder, recorded clip) like the iOS view's `@State`;
 * [isSending] and the reply target come from [CommentsModel].
 */
@Composable
internal fun CommentComposer(
    replyingTo: String?,
    isSending: Boolean,
    onCancelReply: () -> Unit,
    onSend: (draft: CommentDraft, onSuccess: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    onInputFocused: () -> Unit = {},
) {
    var draftText by rememberSaveable { mutableStateOf("") }
    var recorded by remember { mutableStateOf<RecordedClip?>(null) }
    val recorder = rememberAudioRecorder()
    val recorderState by recorder.state.collectAsStateWithLifecycle()

    fun discard(clip: RecordedClip) {
        if (AudioPlaybackCenter.playingId.value == draftAudioId(clip)) AudioPlaybackCenter.stop()
        clip.file.delete()
        if (recorded == clip) recorded = null
    }

    // An unsent recording dies with the sheet (it lives in cacheDir either way).
    // Reads the state itself, not a recomposition-updated copy: a clip handed
    // over just before disposal (the recorder finishing on ON_STOP) is still
    // deleted.
    DisposableEffect(Unit) {
        onDispose { recorded?.file?.delete() }
    }

    CommentComposerBar(
        replyingTo = replyingTo,
        text = draftText,
        onTextChange = { draftText = it },
        recorderState = recorderState,
        recorded = recorded,
        isSending = isSending,
        onCancelReply = onCancelReply,
        onStartRecording = { recorder.start(COMMENT_MAX_AUDIO_SECONDS) { clip -> recorded = clip } },
        onCancelRecording = recorder::cancel,
        onStopRecording = { recorder.stop()?.let { recorded = it } },
        onDiscardRecording = { recorded?.let(::discard) },
        onDismissFailure = recorder::dismissFailure,
        onSend = {
            val clip = recorded
            onSend(composerDraft(draftText, clip)) {
                draftText = ""
                // Uploaded — the temp file has done its job.
                if (clip != null) discard(clip)
            }
        },
        modifier = modifier,
        onTextFieldFocused = onInputFocused,
    )
}

/** Stateless composer bar — every state is renderable from plain data (debug catalog). */
@Composable
internal fun CommentComposerBar(
    replyingTo: String?,
    text: String,
    onTextChange: (String) -> Unit,
    recorderState: RecorderState,
    recorded: RecordedClip?,
    isSending: Boolean,
    onCancelReply: () -> Unit,
    onStartRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
    onDismissFailure: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onTextFieldFocused: () -> Unit = {},
    /** Catalog only: pin the failure row's Settings link instead of checking the real permission. */
    settingsLinkOverride: Boolean? = null,
) {
    val colors = DrokpoTheme.colors
    val canSend = composerCanSend(text, recorded)
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.bar)
            // At the half-height stop the bar is lifted over the list, so rows sit
            // underneath it. A pointer-input node makes the whole bar a hit target:
            // a tap on its padding, the reply chip text or the timer must not land
            // on a vote / Reply / long-press under it. Nothing is consumed, so the
            // field, buttons and the sheet's drag still get every event.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            },
    ) {
        if (replyingTo != null) {
            ReplyChip(replyingTo, onCancelReply)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                // 16 at the leading edge like iOS; trailing icon buttons carry their own inset.
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (recorderState) {
                RecorderState.Idle -> IdleContent(
                    text = text,
                    onTextChange = onTextChange,
                    onTextFieldFocused = onTextFieldFocused,
                    recorded = recorded,
                    onStartRecording = onStartRecording,
                    onDiscardRecording = onDiscardRecording,
                )
                is RecorderState.Recording -> RecordingContent(
                    elapsedSeconds = recorderState.elapsedSeconds,
                    onCancel = onCancelRecording,
                    onStop = onStopRecording,
                )
                is RecorderState.Failed -> if (settingsLinkOverride == null) {
                    RecorderFailureRow(
                        message = recorderState.message,
                        onDismiss = onDismissFailure,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                } else {
                    RecorderFailureRowContent(
                        message = recorderState.message,
                        showSettingsLink = settingsLinkOverride,
                        onOpenSettings = {},
                        onDismiss = onDismissFailure,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                }
            }
            // iOS hid the button while sending (canSend included !isSending), so its
            // spinner never showed; keep the slot and swap the arrow for the spinner.
            if (canSend || isSending) {
                SendButton(isSending = isSending, onSend = onSend)
            }
        }
    }
}

@Composable
private fun ReplyChip(name: String, onCancel: () -> Unit) {
    val colors = DrokpoTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Replying to $name",
            style = DrokpoTheme.typography.caption,
            color = colors.secondaryLabel,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onCancel)
                .padding(4.dp),
        ) {
            Icon(Icons.Filled.Cancel, contentDescription = "Cancel reply", tint = colors.secondaryLabel, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RowScope.IdleContent(
    text: String,
    onTextChange: (String) -> Unit,
    onTextFieldFocused: () -> Unit,
    recorded: RecordedClip?,
    onStartRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
) {
    val colors = DrokpoTheme.colors
    if (recorded != null) {
        AudioBubbleView(
            id = draftAudioId(recorded),
            url = Uri.fromFile(recorded.file).toString(),
            durationSec = recorded.seconds,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDiscardRecording) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete recording", tint = colors.destructive)
        }
    } else {
        ComposerTextField(text, onTextChange, onTextFieldFocused, Modifier.weight(1f))
        IconButton(onClick = onStartRecording) {
            Icon(Icons.Filled.Mic, contentDescription = "Record a voice comment", tint = colors.accent)
        }
    }
}

/** iOS `TextField("Add a comment…", axis: .vertical).lineLimit(1...4)` on a `.quaternary` capsule. */
@Composable
private fun ComposerTextField(
    text: String,
    onTextChange: (String) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val style = DrokpoTheme.typography.body.copy(color = colors.label)
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        textStyle = style,
        minLines = 1,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(colors.fill, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text("Add a comment…", style = style, color = colors.placeholderText, maxLines = 1)
                }
                inner()
            }
        },
    )
}

@Composable
private fun RowScope.RecordingContent(elapsedSeconds: Int, onCancel: () -> Unit, onStop: () -> Unit) {
    val colors = DrokpoTheme.colors
    Row(
        Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(colors.destructive, CircleShape))
        Text(recordingLabel(elapsedSeconds), style = DrokpoTheme.typography.caption.monospacedDigit(), color = colors.label)
        Spacer(Modifier.weight(1f))
    }
    IconButton(onClick = onCancel) {
        Icon(Icons.Filled.Cancel, contentDescription = "Cancel recording", tint = colors.secondaryLabel)
    }
    IconButton(onClick = onStop) {
        Icon(Icons.Filled.StopCircle, contentDescription = "Stop recording", tint = colors.destructive)
    }
}

@Composable
private fun SendButton(isSending: Boolean, onSend: () -> Unit) {
    if (isSending) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner() }
    } else {
        IconButton(onClick = onSend) {
            Icon(
                Icons.Filled.ArrowCircleUp,
                contentDescription = "Send",
                tint = DrokpoTheme.colors.accent,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
