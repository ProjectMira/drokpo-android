package app.drokpo.android.features.feed

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * The action buttons floating under the Discover deck and inside the
 * expanded profile sheet: undo · pass · like · share. Pass/like are the
 * primary pair; undo and share render smaller and only when their action is
 * provided (the expanded profile sheet shows just pass/like — share lives in
 * its toolbar). Sized proportionally to screen width so they read
 * consistently across devices. (Port of Shared/SwipeActionButtons, CONTRACT §B.4.)
 */
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
    // iOS: min(72, UIScreen.main.bounds.width * 0.17) — the window, not the container.
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    // (Previews can report an empty window; fall back to the cap.)
    val diameter = if (windowWidth > 0.dp) min(72.dp, windowWidth * 0.17f) else 72.dp
    val smallDiameter = diameter * 0.72f

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onUndo != null) {
            SwipeActionButton(
                icon = Icons.AutoMirrored.Rounded.Undo,
                tint = colors.orange,
                diameter = smallDiameter,
                label = "Undo last swipe",
                enabled = !undoDisabled,
                onClick = onUndo,
            )
        }
        SwipeActionButton(icon = Icons.Rounded.Close, tint = colors.accent, diameter = diameter, label = "Pass", onClick = onPass)
        SwipeActionButton(icon = Icons.Rounded.Favorite, tint = colors.brandRed, diameter = diameter, label = "Like", onClick = onLike)
        if (onShare != null) {
            SwipeActionButton(
                icon = Icons.Rounded.IosShare,
                tint = colors.accent,
                diameter = smallDiameter,
                label = "Share",
                enabled = !shareDisabled,
                onClick = onShare,
            )
        }
    }
}

object SwipeActionButtonsDefaults {
    /**
     * Vertical space deck cards must keep free at the bottom so their text
     * never sits underneath these overlaid buttons (button diameter + the
     * deck's bottom padding + breathing room).
     */
    val DeckClearance: Dp = 112.dp
}

/**
 * One circular button: tinted glyph on the system background with a soft
 * shadow; presses shrink it to 90 % (iOS PressableButtonStyle, spring 0.2 s)
 * instead of a ripple. Disabled → 40 % opacity.
 */
@Composable
private fun SwipeActionButton(
    icon: ImageVector,
    tint: Color,
    diameter: Dp,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 1000f),
        label = "swipeButtonPress",
    )
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .size(diameter)
            .shadow(elevation = 4.dp, shape = CircleShape)
            .background(DrokpoTheme.colors.background, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(diameter * 0.42f))
    }
}

@DrokpoPreviews
@Composable
private fun SwipeActionButtonsPreview() {
    DrokpoTheme {
        Column(
            Modifier
                .background(DrokpoTheme.colors.secondaryBackground)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SwipeActionButtons(onPass = {}, onLike = {}, onUndo = {}, onShare = {})
            SwipeActionButtons(onPass = {}, onLike = {}, onUndo = {}, undoDisabled = true, onShare = {}, shareDisabled = true)
            SwipeActionButtons(onPass = {}, onLike = {})
            Text("Profile detail (Discover)", color = DrokpoTheme.colors.secondaryLabel)
        }
    }
}
