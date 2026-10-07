package app.drokpo.android.features.chats

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.drokpo.android.features.shared.audio.AudioBubbleView
import app.drokpo.android.features.shared.audio.RecordedClip
import app.drokpo.android.features.shared.audio.RecorderFailureRow
import app.drokpo.android.features.shared.audio.RecorderState
import app.drokpo.android.features.shared.audio.rememberAudioRecorder
import app.drokpo.android.ui.components.Spinner
import app.drokpo.android.ui.components.rememberPhotoPicker
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.monospacedDigit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** iOS ChatInputBar's alert when the picked photo can't be read. */
internal const val PHOTO_LOAD_FAILED = "That photo couldn't be loaded — try picking another one."

/**
 * Text, photo, or voice — pinned under the message list. Voice recording mirrors
 * CommentComposerBar's states (idle/recording/preview) with a longer 120s cap; a photo is picked
 * and sent immediately (no preview step). The draft, the recorded clip and the sending flag live in
 * [model] (iOS keeps them in the pushed view's `@State`), so they survive a pushed profile or a tab
 * switch; only the recorder, which needs this composition's permission launcher, lives here.
 */
@Composable
internal fun ChatInputBar(model: ChatThreadModel, modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val recorder = rememberAudioRecorder()
    val recorderState by recorder.state.collectAsStateWithLifecycle()
    val recordedClip by model.recordedClip.collectAsStateWithLifecycle()
    val isSending by model.isSending.collectAsStateWithLifecycle()

    val pickPhoto = rememberPhotoPicker(maxItems = 1) { uris ->
        val uri = uris.firstOrNull() ?: return@rememberPhotoPicker
        model.sendPhoto(ChatDraft.Photo(uri)) { canLoadImage(appContext, it) }
    }

    ChatInputBarContent(
        text = model.draft,
        onTextChange = model::updateDraft,
        recorderState = recorderState,
        recordedClip = recordedClip,
        isSending = isSending,
        onPickPhoto = pickPhoto,
        onStartRecording = {
            recorder.start(maxSeconds = ChatThreadModel.MAX_VOICE_SECONDS) { clip -> model.setRecordedClip(clip) }
        },
        onCancelRecording = { recorder.cancel() },
        onStopRecording = { recorder.stop()?.let(model::setRecordedClip) },
        onDiscardClip = model::discardClip,
        onDismissFailure = { recorder.dismissFailure() },
        onSend = model::sendCurrentDraft,
        modifier = modifier,
    )
}

/**
 * Stateless input bar on the bar material: the recorder's failure row, the recording timer, the
 * recorded-clip preview, or the photo button + capsule text field + mic/send — with a spinner over
 * it while sending.
 */
@Composable
internal fun ChatInputBarContent(
    text: String,
    onTextChange: (String) -> Unit,
    recorderState: RecorderState,
    recordedClip: RecordedClip?,
    isSending: Boolean,
    onPickPhoto: () -> Unit,
    onStartRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDiscardClip: () -> Unit,
    onDismissFailure: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(DrokpoTheme.colors.bar)
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (recorderState) {
                RecorderState.Idle -> IdleContent(
                    text = text,
                    onTextChange = onTextChange,
                    recordedClip = recordedClip,
                    isSending = isSending,
                    onPickPhoto = onPickPhoto,
                    onStartRecording = onStartRecording,
                    onDiscardClip = onDiscardClip,
                    onSend = onSend,
                )
                is RecorderState.Recording -> RecordingContent(
                    elapsedSeconds = recorderState.elapsedSeconds,
                    onCancel = onCancelRecording,
                    onStop = onStopRecording,
                )
                is RecorderState.Failed -> RecorderFailureRow(
                    message = recorderState.message,
                    onDismiss = onDismissFailure,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 4.dp),
                )
            }
        }
        if (isSending) {
            Spinner(Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun RowScope.IdleContent(
    text: String,
    onTextChange: (String) -> Unit,
    recordedClip: RecordedClip?,
    isSending: Boolean,
    onPickPhoto: () -> Unit,
    onStartRecording: () -> Unit,
    onDiscardClip: () -> Unit,
    onSend: () -> Unit,
) {
    val colors = DrokpoTheme.colors
    if (recordedClip != null) {
        AudioBubbleView(
            id = "draft-${recordedClip.file.name}",
            url = Uri.fromFile(recordedClip.file).toString(),
            durationSec = recordedClip.seconds,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        BarIconButton(onClick = onDiscardClip, tint = colors.destructive) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete recording")
        }
        SendButton(enabled = !isSending, onClick = onSend)
    } else {
        BarIconButton(onClick = onPickPhoto, enabled = !isSending) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Send a photo")
        }
        MessageField(text = text, onTextChange = onTextChange, modifier = Modifier.weight(1f))
        if (text.isBlank()) {
            BarIconButton(onClick = onStartRecording, enabled = !isSending) {
                Icon(Icons.Filled.Mic, contentDescription = "Record a voice message")
            }
        } else {
            SendButton(enabled = !isSending, onClick = onSend)
        }
    }
}

/** Red dot, "{n}s / 120s", cancel (X) and stop. */
@Composable
private fun RowScope.RecordingContent(elapsedSeconds: Int, onCancel: () -> Unit, onStop: () -> Unit) {
    val colors = DrokpoTheme.colors
    Row(
        Modifier
            .weight(1f)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(colors.destructive, CircleShape),
        )
        Text(
            "${elapsedSeconds}s / ${ChatThreadModel.MAX_VOICE_SECONDS}s",
            style = DrokpoTheme.typography.caption.monospacedDigit(),
            color = colors.label,
        )
    }
    BarIconButton(onClick = onCancel, tint = colors.secondaryLabel) {
        Icon(Icons.Filled.Cancel, contentDescription = "Cancel recording")
    }
    BarIconButton(onClick = onStop, tint = colors.destructive) {
        Icon(Icons.Filled.StopCircle, contentDescription = "Stop recording")
    }
}

/**
 * `TextField("Message…", axis: .vertical).lineLimit(1...4)` on a quaternary capsule. The capsule and
 * its padding are drawn in the decoration box, so the whole capsule is the field's tap target.
 */
@Composable
private fun MessageField(text: String, onTextChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = DrokpoTheme.colors
    val style = DrokpoTheme.typography.body
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier,
        textStyle = style.copy(color = colors.label),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        minLines = 1,
        maxLines = 4,
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .background(colors.fill, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text("Message…", style = style, color = colors.placeholderText, maxLines = 1)
                }
                innerTextField()
            }
        },
    )
}

/** `arrow.up.circle.fill` at 30pt. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    BarIconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Filled.ArrowCircleUp, contentDescription = "Send", modifier = Modifier.size(30.dp))
    }
}

/** A toolbar-style icon button: accent (or [tint]) when enabled, grey when not. */
@Composable
private fun BarIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = DrokpoTheme.colors.accent,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = DrokpoTheme.colors.tertiaryLabel,
        ),
        content = content,
    )
}

/**
 * Whether a picked photo can be read at all (iOS `loadTransferable` → `UIImage(data:)`); a decode
 * of the bounds only, off the main thread. Uploading downscales it later.
 */
private suspend fun canLoadImage(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
            true
        } ?: false
        opened && options.outWidth > 0 && options.outHeight > 0
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
