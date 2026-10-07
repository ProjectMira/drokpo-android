package app.drokpo.android.features.shared.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.drokpo.android.ui.components.openAppSettings
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * Replaces a composer's input while its [AudioRecorder] is [RecorderState.Failed]:
 * the reason, a shortcut to the app's Settings page when mic access is off, and a
 * dismiss button back to the normal input so typing keeps working.
 */
@Composable
fun RecorderFailureRow(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Re-checked on every resume, so the link disappears once the user has
    // turned the microphone on in Settings and come back.
    var micDenied by remember { mutableStateOf(!isMicPermissionGranted(context)) }
    LifecycleResumeEffect(context) {
        micDenied = !isMicPermissionGranted(context)
        onPauseOrDispose { }
    }
    RecorderFailureRowContent(
        message = message,
        showSettingsLink = micDenied,
        onOpenSettings = { context.openAppSettings() },
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

/** Stateless body of [RecorderFailureRow] (the catalog shows both link variants). */
@Composable
internal fun RecorderFailureRowContent(
    message: String,
    showSettingsLink: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = typography.caption,
            color = colors.secondaryLabel,
            // iOS `Text` + `Spacer(minLength: 0)`: the message takes the slack, the controls hug the trailing edge.
            modifier = Modifier.weight(1f),
        )
        if (showSettingsLink) {
            Text(
                "Settings",
                style = typography.caption.bold(),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onOpenSettings)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Cancel,
                contentDescription = "Dismiss",
                tint = colors.secondaryLabel,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
