package app.drokpo.android.features.shared.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.semibold
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One trailing swipe button (iOS `.swipeActions(edge: .trailing) { Button(…) }`). */
@Immutable
internal data class SwipeAction(val label: String, val color: Color, val onClick: () -> Unit)

private enum class SwipeValue { Closed, Open }

private val SwipeActionWidth = 76.dp

/**
 * iOS `.swipeActions(edge: .trailing)` for a plain-list row: dragging the row
 * left reveals [actions] (the first one at the trailing edge, like iOS), and a
 * tap on one runs it and closes the row. At most one row is open at a time —
 * [openId] is the open row, [onOpenChange] reports this row opening/closing.
 * The actions are also exposed as accessibility custom actions.
 */
@Composable
internal fun SwipeActionsBox(
    id: String,
    actions: List<SwipeAction>,
    openId: String?,
    onOpenChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (actions.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val revealPx = with(density) { (SwipeActionWidth * actions.size).toPx() }
    // Keyed by id too: if this slot is ever reused for another row, that row
    // starts closed with a fresh drag state instead of inheriting this one.
    val state = remember(id, revealPx) {
        AnchoredDraggableState(
            initialValue = if (openId == id) SwipeValue.Open else SwipeValue.Closed,
            anchors = DraggableAnchors {
                SwipeValue.Closed at 0f
                SwipeValue.Open at -revealPx
            },
        )
    }
    val scope = rememberCoroutineScope()
    val currentId by rememberUpdatedState(id)
    val currentOpenId by rememberUpdatedState(openId)
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)

    // Another row opened (or the list scrolled): close this one.
    LaunchedEffect(openId, state) {
        if (openId != currentId && state.targetValue == SwipeValue.Open) state.animateTo(SwipeValue.Closed)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collect { value ->
            when {
                value == SwipeValue.Open -> currentOnOpenChange(currentId)
                currentOpenId == currentId -> currentOnOpenChange(null)
            }
        }
    }
    val revealed by remember(state) {
        derivedStateOf { state.offset.let { !it.isNaN() && it < -0.5f } }
    }
    val close: () -> Unit = { scope.launch { state.animateTo(SwipeValue.Closed) } }

    Box(
        modifier
            .clipToBounds()
            .semantics {
                customActions = actions.map { action ->
                    CustomAccessibilityAction(action.label) {
                        action.onClick()
                        true
                    }
                }
            },
    ) {
        if (revealed) {
            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
                // iOS lists trailing actions from the edge inwards.
                actions.asReversed().forEach { action ->
                    Box(
                        Modifier
                            .width(SwipeActionWidth)
                            .fillMaxHeight()
                            .background(action.color)
                            .clickable(role = Role.Button) {
                                close()
                                action.onClick()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            action.label,
                            style = DrokpoTheme.typography.subheadline.semibold(),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(state.offset.takeUnless { it.isNaN() }?.roundToInt() ?: 0, 0) }
                .anchoredDraggable(state, Orientation.Horizontal),
        ) {
            content()
            if (revealed) {
                // Tapping an open row closes it instead of acting on its contents (iOS).
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
                )
            }
        }
    }
}
