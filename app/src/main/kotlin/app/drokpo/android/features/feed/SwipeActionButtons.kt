package app.drokpo.android.features.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoTheme

/** Port of Shared/SwipeActionButtons: undo · pass · like · share. undo/share hidden when their
 *  callback is null and rendered at 0.72× size; disabled → 40 % opacity.
 *  (CONTRACT §B.4 — stub: plain tonal icon buttons, no sizing/styling yet.) */
@Composable
fun SwipeActionButtons(
    onPass: () -> Unit,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
    onUndo: (() -> Unit)? = null,
    undoDisabled: Boolean = false,
    onShare: (() -> Unit)? = null,
    shareDisabled: Boolean = false,
) {
    val colors = DrokpoTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onUndo != null) {
            FilledTonalIconButton(onClick = onUndo, enabled = !undoDisabled) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = colors.orange)
            }
        }
        FilledTonalIconButton(onClick = onPass) {
            Icon(Icons.Filled.Close, contentDescription = "Pass", tint = colors.accent)
        }
        FilledTonalIconButton(onClick = onLike) {
            Icon(Icons.Filled.Favorite, contentDescription = "Like", tint = colors.brandRed)
        }
        if (onShare != null) {
            FilledTonalIconButton(onClick = onShare, enabled = !shareDisabled) {
                Icon(Icons.Filled.Share, contentDescription = "Share", tint = colors.accent)
            }
        }
    }
}

object SwipeActionButtonsDefaults {
    /** Space deck cards keep free at the bottom so text never sits under the overlaid buttons. */
    val DeckClearance: Dp = 112.dp
}
