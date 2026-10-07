package app.drokpo.android.features.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.Photo
import app.drokpo.android.ui.components.OverlayTag
import app.drokpo.android.ui.theme.DrokpoTheme
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val ADD_TILE_KEY = "drokpo.profile.addPhoto"
private val TileShape = RoundedCornerShape(10.dp)
private val TileWidth = 90.dp
private val TileHeight = 120.dp
private val StripVerticalPadding = 8.dp

/**
 * ProfileView's photos row: horizontally scrolling 90×120 tiles (X delete,
 * "Primary" badge on the first) plus a "+" tile while there are fewer than six.
 *
 * Reorder: iOS drags a photo over another with `onDrag`/`onDrop` and
 * `PhotoDropDelegate` (which lives inside the ScrollView, where `.onMove`
 * doesn't work); it swaps optimistically on `dropEntered` and commits on drop.
 * Here a long press on a photo picks the tile up, it follows the finger, and
 * whenever its centre is over another photo the model moves it onto that slot
 * ([onDragOver]); lifting the finger calls [onDragEnd], which commits.
 * The strip auto-scrolls while the tile is held against either edge. A long
 * press anywhere else (the gaps, the "+" tile) is left alone, so the strip
 * keeps scrolling and "+" still opens the picker.
 */
@Composable
internal fun PhotoStrip(
    photos: List<Photo>,
    canAdd: Boolean,
    onAddPhoto: () -> Unit,
    onDeletePhoto: (Photo) -> Unit,
    onDragStart: (photoId: String) -> Unit,
    onDragOver: (targetId: String) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Lazy keys must be unique; a doc that somehow lists one storage path twice must not crash the tab.
    val tiles = remember(photos) { photos.distinctBy { it.storagePath } }
    val rowState = rememberLazyListState()
    val dragState = remember { PhotoDragState() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxScrollStep = with(density) { 14.dp.toPx() }
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragOver by rememberUpdatedState(onDragOver)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    fun laidOutTiles(): List<StripTile> =
        rowState.layoutInfo.visibleItemsInfo.map { StripTile(key = it.key, index = it.index, start = it.offset, size = it.size) }

    fun checkSwap() {
        val key = dragState.key ?: return
        val laidOut = laidOutTiles()
        val dragged = laidOut.firstOrNull { it.key == key } ?: return
        if (dragState.gate.blocks(dragged.index)) return
        val middle = dragState.visualStart() + dragged.size / 2f
        val target = photoTileAt(middle, laidOut, addTileKey = ADD_TILE_KEY, except = key) ?: return
        // LazyRow keeps its first visible item in place by key; when that item is the one
        // moving, pin the scroll position by index instead so the strip doesn't jump.
        val first = rowState.firstVisibleItemIndex
        if (dragged.index == first || target.index == first) {
            rowState.requestScrollToItem(first, rowState.firstVisibleItemScrollOffset)
        }
        dragState.gate.arm(dragged.index)
        currentOnDragOver(target.key as String)
    }

    fun finishDrag() {
        val key = dragState.key ?: return
        val from = dragState.translation(rowState, key)
        dragState.reset()
        scope.launch { dragState.settle(key, from) }
        currentOnDragEnd()
    }

    // Auto-scroll while the picked-up tile is pushed past either edge of the strip.
    LaunchedEffect(dragState.key) {
        if (dragState.key == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val step = dragState.overscroll(rowState, maxScrollStep)
            if (step != 0f) {
                rowState.scrollBy(step)
                checkSwap()
            }
        }
    }

    LazyRow(
        state = rowState,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                // Not detectDragGesturesAfterLongPress: that consumes every move (and the up)
                // after *any* long press, so one on a gap or on "+" froze the strip and
                // swallowed the picker tap. Here only a long press on a photo claims the gesture.
                val tileTop = StripVerticalPadding.toPx()
                val tileBottom = tileTop + TileHeight.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    if (longPress.position.y !in tileTop..tileBottom) return@awaitEachGesture
                    val tile = photoTileAt(longPress.position.x, laidOutTiles(), addTileKey = ADD_TILE_KEY)
                        ?: return@awaitEachGesture
                    val key = tile.key as String
                    dragState.start(key, tile.start)
                    currentOnDragStart(key)
                    try {
                        // After pick-up the gesture is the strip's alone. Read and consume it in the
                        // Initial pass (this modifier is the LazyRow's outermost, so it sees events
                        // first). In the Main pass, children see each event before the strip does:
                        // a photo's X button would take the final up as a tap and delete the photo
                        // after a still long press on it, and the row's own scroller could grab the
                        // moves and cancel the reorder drag.
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == longPress.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                break
                            }
                            dragState.distance += change.positionChangeIgnoreConsumed().x
                            checkSwap()
                            change.consume()
                        }
                    } finally {
                        // Lifted, cancelled by another consumer, or the strip left composition.
                        finishDrag()
                    }
                }
            },
        // Main-axis padding stays 0 so item offsets and touch x share one coordinate space.
        contentPadding = PaddingValues(vertical = StripVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(tiles, key = { _, photo -> photo.storagePath }) { index, photo ->
            val key = photo.storagePath
            val tileModifier = when (key) {
                dragState.key -> Modifier
                    .zIndex(1f)
                    .graphicsLayer {
                        translationX = dragState.translation(rowState, key)
                        scaleX = 1.06f
                        scaleY = 1.06f
                        shadowElevation = 8.dp.toPx()
                        shape = TileShape
                        clip = true
                    }
                dragState.settleKey -> Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationX = dragState.settleOffset.value }
                else -> Modifier.animateItem()
            }
            PhotoTile(
                photo = photo,
                isPrimary = index == 0,
                onDelete = { onDeletePhoto(photo) },
                onMoveEarlier = tiles.getOrNull(index - 1)?.let { previous ->
                    {
                        onDragStart(key)
                        onDragOver(previous.storagePath)
                        onDragEnd()
                    }
                },
                onMoveLater = tiles.getOrNull(index + 1)?.let { next ->
                    {
                        onDragStart(key)
                        onDragOver(next.storagePath)
                        onDragEnd()
                    }
                },
                modifier = tileModifier,
            )
        }
        if (canAdd) {
            item(key = ADD_TILE_KEY) {
                AddPhotoTile(onClick = onAddPhoto, modifier = Modifier.animateItem())
            }
        }
    }
}

/** One photo: RemotePhotoView, `xmark.circle.fill` delete at the top-trailing corner, "Primary" badge. */
@Composable
private fun PhotoTile(
    photo: Photo,
    isPrimary: Boolean,
    onDelete: () -> Unit,
    onMoveEarlier: (() -> Unit)?,
    onMoveLater: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    Box(
        modifier
            .size(TileWidth, TileHeight)
            .clip(TileShape)
            .semantics {
                contentDescription = if (isPrimary) "Primary photo" else "Photo"
                // Drag-to-reorder has no TalkBack equivalent; offer the same moves as actions.
                customActions = listOfNotNull(
                    onMoveEarlier?.let { move -> CustomAccessibilityAction("Move earlier") { move(); true } },
                    onMoveLater?.let { move -> CustomAccessibilityAction("Move later") { move(); true } },
                )
            },
    ) {
        RemotePhotoView(photo = photo, modifier = Modifier.matchParentSize())
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .clickable(role = Role.Button, onClickLabel = "Remove photo", onClick = onDelete)
                .semantics { contentDescription = "Remove photo" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(colors.photoScrimStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = colors.onPhoto,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        if (isPrimary) {
            OverlayTag(
                text = "Primary",
                small = true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
            )
        }
    }
}

/** The PhotosPicker label: `RoundedRectangle(cornerRadius: 10).fill(.quaternary)` with a "plus". */
@Composable
private fun AddPhotoTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DrokpoTheme.colors
    Box(
        modifier
            .size(TileWidth, TileHeight)
            .clip(TileShape)
            .background(colors.fill)
            .clickable(role = Role.Button, onClickLabel = "Add photo", onClick = onClick)
            .semantics { contentDescription = "Add photo" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
    }
}

/**
 * Gesture bookkeeping for one drag (UI-only; the order itself lives in ProfileModel).
 * The arithmetic — hit-testing, translation, auto-scroll, the ping-pong [gate] — lives in
 * ProfileLogic.kt so it is unit-tested on the JVM.
 */
@Stable
private class PhotoDragState {
    /** Key (storage path) of the tile under the finger, null when idle. */
    var key: String? by mutableStateOf(null)
        private set

    /** Horizontal distance the finger has travelled since pick-up. */
    var distance: Float by mutableFloatStateOf(0f)

    /** Where the tile was laid out at pick-up, in viewport coordinates. */
    private var pickUpStart = 0

    /** Holds swap checks until the strip has been laid out in the order last requested. */
    val gate = LayoutGate()

    /** The tile that was just dropped, sliding from the finger back into its slot. */
    var settleKey: String? by mutableStateOf(null)
        private set
    val settleOffset = Animatable(0f)

    fun start(key: String, start: Int) {
        this.key = key
        pickUpStart = start
        distance = 0f
        gate.reset()
    }

    fun reset() {
        key = null
        distance = 0f
        gate.reset()
    }

    /** The tile's leading edge as the finger holds it, in viewport coordinates. */
    fun visualStart(): Float = pickUpStart + distance

    /** Offset that keeps the tile under the finger, whatever slot (or scroll) it is laid out at. */
    fun translation(state: LazyListState, key: String): Float {
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        return dragTranslation(pickUpStart, distance, item.offset)
    }

    /** Scroll step for this frame: positive past the trailing edge, negative past the leading one. */
    fun overscroll(state: LazyListState, maxStep: Float): Float {
        val key = key ?: return 0f
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        return autoScrollStep(visualStart(), item.size, state.layoutInfo.viewportEndOffset.toFloat(), maxStep)
    }

    suspend fun settle(key: String, from: Float) {
        settleKey = key
        settleOffset.snapTo(from)
        settleOffset.animateTo(0f, tween(durationMillis = 180))
        if (settleKey == key) settleKey = null
    }
}
