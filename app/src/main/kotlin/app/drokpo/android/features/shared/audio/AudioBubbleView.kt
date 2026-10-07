package app.drokpo.android.features.shared.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.monospacedDigit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A play/pause button + progress bar + duration for a voice clip — used in
 * both chat bubbles and comment rows. Playback goes through the shared
 * [AudioPlaybackCenter], so starting one clip stops any other playing.
 *
 * [url]: https download URL, or a file:// URI for a composer's draft preview.
 * [isOnTintBackground]: true for the sender's own outgoing chat bubble — flips
 * to a light-on-tint palette instead of the default primary/secondary one.
 */
@Composable
fun AudioBubbleView(
    id: String,
    url: String,
    durationSec: Int,
    modifier: Modifier = Modifier,
    isOnTintBackground: Boolean = false,
) {
    val playingId by AudioPlaybackCenter.playingId.collectAsStateWithLifecycle()
    val progress = AudioPlaybackCenter.progress.collectAsStateWithLifecycle()
    val isPlaying = playingId == id
    AudioBubbleContent(
        isPlaying = isPlaying,
        // Read lazily (draw phase / derived label) so only the playing bubble
        // redraws on every ~100 ms progress tick.
        progress = { if (isPlaying) progress.value else 0f },
        durationSec = durationSec,
        onToggle = { AudioPlaybackCenter.play(id, url) },
        modifier = modifier,
        isOnTintBackground = isOnTintBackground,
    )
}

/** Stateless body of [AudioBubbleView] — the catalog renders it in its playing state. */
@Composable
internal fun AudioBubbleContent(
    isPlaying: Boolean,
    progress: () -> Float,
    durationSec: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    isOnTintBackground: Boolean = false,
) {
    val colors = DrokpoTheme.colors
    val foreground = if (isOnTintBackground) colors.onAccent else colors.label
    val currentProgress by rememberUpdatedState(progress)
    val label by remember(durationSec, isPlaying) {
        derivedStateOf { audioDurationLabel(durationSec, isPlaying, currentProgress()) }
    }
    Row(
        modifier.widthIn(min = 140.dp),
        // iOS spacing is 10; the play button's touch target adds 4 on its trailing side.
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = if (isPlaying) "Pause" else "Play", onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                contentDescription = if (isPlaying) "Pause voice message" else "Play voice message",
                tint = foreground,
                modifier = Modifier.size(28.dp),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .drawBehind {
                    val radius = CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(foreground.copy(alpha = 0.25f), cornerRadius = radius)
                    val fraction = if (isPlaying) max(MIN_PROGRESS_FRACTION, currentProgress()) else MIN_PROGRESS_FRACTION
                    drawRoundRect(
                        foreground,
                        size = Size(size.width * fraction.coerceAtMost(1f), size.height),
                        cornerRadius = radius,
                    )
                },
        )
        Text(
            label,
            style = DrokpoTheme.typography.caption2.monospacedDigit(),
            color = foreground.copy(alpha = 0.8f),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(36.dp),
        )
    }
}

/** The iOS bar never shrinks below a sliver, so an unplayed clip still reads as a track. */
private const val MIN_PROGRESS_FRACTION = 0.03f

/** "m:ss" — the time remaining while playing, else the clip's total length. */
internal fun audioDurationLabel(durationSec: Int, isPlaying: Boolean, progress: Float): String {
    val total = max(0, durationSec)
    val remaining = if (isPlaying) max(0, total - (progress * total).roundToInt()) else total
    return "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"
}
