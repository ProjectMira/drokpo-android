package app.drokpo.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * iOS `ProgressView()`: a small grey spinner (not the Material accent ring).
 * Inside a prominent button iOS tints it white — pass [color].
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    color: Color = DrokpoTheme.colors.secondaryLabel,
    size: Dp = 20.dp,
    strokeWidth: Dp = 2.dp,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        strokeWidth = strokeWidth,
    )
}

/**
 * Full-area loading placeholder (`ProgressView().frame(maxHeight: .infinity)`
 * or `.overlay { if isLoading { ProgressView() } }`): a centred [Spinner].
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Spinner(size = 28.dp, strokeWidth = 2.5.dp)
    }
}

/**
 * `ContentUnavailableView(title, systemImage:, description:)` and the
 * hand-built empty VStacks (CONTRACT.md §A.12): secondary icon, title,
 * secondary message, optional [action] (e.g. a Retry button), centred. Fills
 * the available width; pass `Modifier.fillMaxSize()` to centre on screen too.
 *
 * Default look = ContentUnavailableView (52dp icon, `.title2.bold()` title).
 * [compact] = the custom empty states in Likes / Chats (48dp icon,
 * `.headline` title, `.subheadline` message).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    compact: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = if (compact) 16.dp else 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = colors.secondaryLabel,
                modifier = Modifier
                    .size(if (compact) 48.dp else 52.dp)
                    .padding(bottom = if (compact) 0.dp else 4.dp),
            )
            Text(
                title,
                style = if (compact) typography.headline else typography.title2.bold(),
                color = colors.label,
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Text(
                    message,
                    style = typography.subheadline,
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(4.dp))
                action()
            }
        }
    }
}

/**
 * Load failure with a way out: warning icon, [title] (iOS alerts say
 * "Something went wrong"), the error message (`throwable.userMessage()`), and
 * a "Retry" button when [onRetry] is set — same copy as the iOS root
 * "Couldn't load your profile." screen.
 */
@Composable
fun ErrorState(
    message: String?,
    modifier: Modifier = Modifier,
    title: String = "Something went wrong",
    onRetry: (() -> Unit)? = null,
    retrying: Boolean = false,
) {
    EmptyState(
        icon = Icons.Rounded.WarningAmber,
        title = title,
        modifier = modifier,
        message = message,
        compact = true,
        action = onRetry?.let { retry ->
            { ProminentButton("Retry", onClick = retry, loading = retrying) }
        },
    )
}

@DrokpoPreviews
@Composable
private fun StatesPreview() {
    DrokpoTheme {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 16.dp)) {
            EmptyState(
                icon = Icons.Outlined.PanTool,
                title = "No blocked users",
                message = "People you block from the feed will show up here.",
            )
            Row {
                EmptyState(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "No likes yet",
                    message = "Likes you receive will show up here.",
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                EmptyState(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = "No chats yet",
                    message = "When you and someone else like each other, you can start chatting here.",
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            ErrorState(message = "The Internet connection appears to be offline.", onRetry = {})
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Spacer(Modifier.width(16.dp))
                Box(Modifier.size(80.dp)) { LoadingState() }
            }
        }
    }
}
