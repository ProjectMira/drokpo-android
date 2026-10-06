package app.drokpo.android.features.shared.audio

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.drokpo.android.ui.theme.DrokpoTheme

/** Port of RecorderFailureRow: message, "Settings" link (openAppSettings) when the mic permission is
 *  denied, dismiss X (a11y "Dismiss"). (CONTRACT §B.10 — stub: message + dismiss.) */
@Composable
fun RecorderFailureRow(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            message,
            style = DrokpoTheme.typography.caption,
            color = DrokpoTheme.colors.secondaryLabel,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = DrokpoTheme.colors.secondaryLabel)
        }
    }
}
