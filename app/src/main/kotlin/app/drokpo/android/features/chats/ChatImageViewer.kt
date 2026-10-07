package app.drokpo.android.features.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.ui.components.FullScreenCover
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * Full-screen tap-through for a chat photo — plain black background, scaled-to-fit, tap anywhere
 * to dismiss (iOS `.fullScreenCover`).
 */
@Composable
internal fun ChatImageViewer(url: String, onDismiss: () -> Unit) {
    FullScreenCover(onDismissRequest = onDismiss) {
        ChatImageViewerContent(url = url, onDismiss = onDismiss)
    }
}

@Composable
internal fun ChatImageViewerContent(url: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LightBarIconsInDialog()
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    ) {
        RemotePhotoView(
            storagePath = "chat-image-viewer",
            url = url,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp),
            contentScale = ContentScale.Fit,
            contentDescription = "Photo",
        )
        // `xmark.circle.fill` in white on a translucent black disc.
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(8.dp),
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(DrokpoTheme.colors.photoScrimStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The viewer is black in both themes, so its dialog window needs light status/navigation bar icons
 * (FullScreenCover matches them to the app theme). A no-op outside a dialog (the debug catalog).
 *
 * FullScreenCover's own SideEffect re-applies the theme's icons when the theme changes. The theme
 * colours are a static composition local, so that change recomposes this whole subtree without
 * skipping, and this effect — registered later in the same pass — runs after it and wins. The cover
 * recomposes otherwise only for new viewer arguments, which recompose this scope as well.
 */
@Composable
private fun LightBarIconsInDialog() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
