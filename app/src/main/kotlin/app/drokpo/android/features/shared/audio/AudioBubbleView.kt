package app.drokpo.android.features.shared.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.drokpo.android.ui.theme.DrokpoTheme

/** Port of AudioBubbleView. url: https download URL or file:// URI (draft preview).
 *  (CONTRACT §B.10 — stub: play/pause toggle + "m:ss" duration.) */
@Composable
fun AudioBubbleView(
    id: String,
    url: String,
    durationSec: Int,
    modifier: Modifier = Modifier,
    isOnTintBackground: Boolean = false,
) {
    val playingId by AudioPlaybackCenter.playingId.collectAsStateWithLifecycle()
    val tint = if (isOnTintBackground) DrokpoTheme.colors.onAccent else DrokpoTheme.colors.accent
    Row(
        modifier.widthIn(min = 140.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val playing = playingId == id
        IconButton(onClick = { AudioPlaybackCenter.play(id, url) }) {
            Icon(
                if (playing) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                contentDescription = if (playing) "Pause" else "Play",
                tint = tint,
            )
        }
        Text(
            "%d:%02d".format(durationSec / 60, durationSec % 60),
            style = DrokpoTheme.typography.caption2,
            color = if (isOnTintBackground) DrokpoTheme.colors.onAccent else DrokpoTheme.colors.secondaryLabel,
        )
    }
}
