package app.drokpo.android.features.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.medium
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class SwipeAnchor { Closed, Open, Full }

/** Width of the revealed action button (iOS sizes it to "Unmatch" plus its padding). */
private val ActionWidth = 92.dp

/**
 * iOS `.swipeActions { Button(label, role: .destructive) { … } }` on a List row: swiping toward
 * the leading edge reveals one red button at the trailing edge. Tapping it — or a full swipe past
 * half the row — runs [onAction] with the row swiped away (iOS animates a destructive action's row
 * out). [onAction] returns true when the action went through (the caller removes the row) or false
 * when it failed: the row then slides back, closed and usable again. Tapping the row while the
 * button shows closes it instead of running [onClick]. TalkBack gets the action as a custom
 * accessibility action on the row.
 *
 * Like a UITableView, the list keeps at most one row open: [isOpen] says whether this row is the
 * open one (a row told it no longer is closes itself), and [onOpenChange] reports this row settling
 * open (true) or closed (false).
 */
@Composable
internal fun SwipeActionRow(
    actionLabel: String,
    onAction: suspend () -> Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isOpen: Boolean = false,
    onOpenChange: (Boolean) -> Unit = {},
    containerColor: Color = DrokpoTheme.colors.secondaryGroupedBackground,
    content: @Composable () -> Unit,
) {
    val colors = DrokpoTheme.colors
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val actionWidthPx = with(density) { ActionWidth.toPx() }
    val state = remember {
        AnchoredDraggableState(
            initialValue = if (isOpen) SwipeAnchor.Open else SwipeAnchor.Closed,
            anchors = DraggableAnchors {
                SwipeAnchor.Closed at 0f
                SwipeAnchor.Open at -actionWidthPx
            },
        )
    }
    val scope = rememberCoroutineScope()
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collect { value ->
            currentOnOpenChange(value == SwipeAnchor.Open)
            if (value == SwipeAnchor.Full && !currentOnAction()) {
                // Failed (possibly within milliseconds, offline): bring the row back. In its own
                // coroutine, so a drag interrupting the animation can't end this collector.
                scope.launch { state.animateTo(SwipeAnchor.Closed) }
            }
        }
    }
    // Another row opened, or the list scrolled.
    LaunchedEffect(isOpen) {
        if (!isOpen && state.settledValue == SwipeAnchor.Open) state.animateTo(SwipeAnchor.Closed)
    }
    val fling = AnchoredDraggableDefaults.flingBehavior(
        state = state,
        positionalThreshold = { distance -> distance * 0.5f },
    )
    val runAction: () -> Unit = { scope.launch { state.animateTo(SwipeAnchor.Full) } }

    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { size ->
                state.updateAnchors(
                    DraggableAnchors {
                        SwipeAnchor.Closed at 0f
                        SwipeAnchor.Open at -actionWidthPx
                        SwipeAnchor.Full at -size.width.toFloat()
                    },
                )
            }
            .anchoredDraggable(
                state = state,
                reverseDirection = isRtl,
                orientation = Orientation.Horizontal,
                flingBehavior = fling,
            ),
    ) {
        val offset = state.offset.takeUnless { it.isNaN() } ?: 0f
        val revealed = abs(offset.coerceAtMost(0f))
        if (revealed > 0f) {
            // The red button fills the revealed space at the trailing edge; past its own width
            // (a full swipe in progress) the label follows the row's edge, like iOS.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(with(density) { revealed.toDp() })
                    .fillMaxHeight()
                    .background(colors.destructive)
                    .clickable(onClick = runAction),
                contentAlignment = if (revealed > actionWidthPx) Alignment.CenterStart else Alignment.Center,
            ) {
                Text(
                    actionLabel,
                    style = DrokpoTheme.typography.subheadline.medium(),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.roundToInt(), 0) }
                .background(containerColor)
                .clickable {
                    if (state.settledValue == SwipeAnchor.Closed && state.targetValue == SwipeAnchor.Closed) {
                        onClick()
                    } else {
                        scope.launch { state.animateTo(SwipeAnchor.Closed) }
                    }
                }
                // On the node TalkBack focuses (the clickable row merges its content), so the
                // action is reachable from the row's actions menu.
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(actionLabel) {
                            runAction()
                            true
                        },
                    )
                },
        ) {
            content()
        }
    }
}
