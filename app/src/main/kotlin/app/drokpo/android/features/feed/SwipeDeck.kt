package app.drokpo.android.features.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.PhotoBand
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.features.shared.sharing.ShareableContent
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Deck geometry (iOS SwipeableWrapper / FeedView.deck).

/** Drag distance past which a released card commits (iOS `swipeThreshold`; distance only, no fling). */
internal val SwipeThreshold: Dp = 110.dp

/** Rotation in degrees = horizontal offset (dp) / 18. */
internal const val ROTATION_DIVISOR = 18f

/** The shortest a committed card flies (iOS ±600); further when the deck is bigger. */
internal val FlyOffDistance: Dp = 600.dp

/** Only the top three cards render. */
internal const val VISIBLE_CARDS = 3

/** Card i behind the top one is scaled by 1 − 0.03·i… */
internal const val DEPTH_SCALE_STEP = 0.03f

/** …and pushed down 10dp·i. */
internal val DepthOffsetStep: Dp = 10.dp

/** Stamps show (fading in over the deck spring) once the card is dragged past 40dp (iOS ±40). */
internal val StampVisibleOffset: Dp = 40.dp

/**
 * Tallest band Android lets an app take from each edge's system back gesture
 * (View.setSystemGestureExclusionRects caps it at 200dp per edge).
 */
private val MaxGestureExclusion: Dp = 200.dp

/** Projection of the release velocity onto the fly-off target (seconds of travel). */
private const val FLY_OFF_VELOCITY_PROJECTION = 0.12f

/** How far past the point where it leaves the deck a committed card is aimed. */
private const val FLY_OFF_OVERSHOOT = 1.15f

/** Hard cap on how long a departing card can stay on the stack. */
private const val FLY_OFF_MAX_MILLIS = 700L

private const val BUTTON_FLY_OFF_MILLIS = 280

/**
 * SwiftUI `.spring(duration: 0.3)`: bounce 0, i.e. critically damped, with
 * stiffness (2π / 0.3 s)² ≈ 439 — it settles without overshooting.
 */
private const val IOS_SPRING_STIFFNESS = 439f

// iOS `.animation(.spring(duration: 0.3), value: offset)`.
private val SpringBackSpec = spring<Offset>(dampingRatio = 1f, stiffness = IOS_SPRING_STIFFNESS)
private val StampFadeSpec = spring<Float>(dampingRatio = 1f, stiffness = IOS_SPRING_STIFFNESS)
private val DragFlyOffSpec = spring<Offset>(dampingRatio = 1f, stiffness = 200f)
private val ButtonFlyOffSpec = tween<Offset>(durationMillis = BUTTON_FLY_OFF_MILLIS, easing = FastOutLinearInEasing)
private val ReturnSpec = spring<Offset>(dampingRatio = 0.8f, stiffness = 300f)
private val DepthSpec = spring<Float>(dampingRatio = 1f, stiffness = 500f)

/**
 * What a released drag means: true = right (like/save/visit/join), false =
 * left (pass), null = spring back. Distance only, like iOS (`translation.width`
 * against ±110): a quick flick short of the threshold never records a like.
 */
internal fun swipeDecision(offsetX: Float, threshold: Float): Boolean? = when {
    offsetX > threshold -> true
    offsetX < -threshold -> false
    else -> null
}

/** Whether a stamp shows for a drag of [offsetDp] (positive = that stamp's direction). */
internal fun stampVisible(offsetDp: Float): Boolean = offsetDp > StampVisibleOffset.value

/** Scale of a card [depth] slots behind the top one. */
internal fun depthScale(depth: Float): Float = 1f - DEPTH_SCALE_STEP * depth

/** Rotation in degrees of a card dragged [offsetX] px horizontally, at [density] px per dp. */
internal fun cardRotation(offsetX: Float, density: Float): Float = offsetX / density / ROTATION_DIVISOR

/**
 * Where a card displaced by [offset] actually draws. iOS applies `.offset`
 * and then `.rotationEffect` about the card's resting centre, so the
 * displacement turns with the card: it swings on an arc (dragged right, it
 * also drops — about 12dp at the threshold) rather than staying level.
 */
internal fun arcTranslation(offset: Offset, rotationDegrees: Float): Offset {
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    return Offset(offset.x * c - offset.y * s, offset.x * s + offset.y * c)
}

/**
 * True once a [width]×[height] card displaced by [offset] (and rotated and
 * swung by it, see [arcTranslation]) no longer overlaps the deck's own
 * bounds — a separating-axis test between the rotated card and the deck.
 */
internal fun isOffDeck(offset: Offset, density: Float, width: Float, height: Float): Boolean {
    val rotation = cardRotation(offset.x, density)
    val shift = arcTranslation(offset, rotation)
    val radians = Math.toRadians(rotation.toDouble())
    val ux = cos(radians).toFloat()
    val uy = sin(radians).toFloat()
    val halfW = width / 2f
    val halfH = height / 2f
    // The deck's axes, then the card's own.
    val axes = arrayOf(1f to 0f, 0f to 1f, ux to uy, -uy to ux)
    for ((ax, ay) in axes) {
        val distance = abs(shift.x * ax + shift.y * ay)
        val cardRadius = halfW * abs(ux * ax + uy * ay) + halfH * abs(-uy * ax + ux * ay)
        val deckRadius = halfW * abs(ax) + halfH * abs(ay)
        if (distance > cardRadius + deckRadius) return true
    }
    return false
}

/**
 * The horizontal offset (px) at which a card held at vertical offset [y]
 * has left a [width]×[height] deck. With the arc this is less than the deck
 * width plus half its height on a phone: the card leaves past the bottom corner.
 */
internal fun flyOffDistance(width: Float, height: Float, density: Float, y: Float = 0f): Float {
    if (width <= 0f || height <= 0f) return FlyOffDistance.value * density
    val step = 4f * density
    val limit = 4f * (width + height)
    var x = step
    while (x < limit) {
        if (isOffDeck(Offset(x, y), density, width, height)) return x
        x += step
    }
    return width + height / 2f
}

/**
 * Motion state of the Discover deck: each visible card's drag offset, the
 * cards already swiped that are still flying off, and the programmatic swipe
 * used by the buttons and the expanded profile sheet. Hoisted (FeedScreen)
 * so swipes started outside the deck animate too.
 *
 * Every commit hands the card to [onCommit] immediately — the model drops it
 * from the deck at once, like iOS — while the card keeps rendering above the
 * stack until it has left the screen.
 */
@Stable
internal class DeckState(private val scope: CoroutineScope) {
    /** Swiped cards still flying off, oldest first. */
    val departing = mutableStateListOf<DeckItem>()

    private val motions = HashMap<String, Animatable<Offset, AnimationVector2D>>()
    private var lastProfileDeparture: Departure? = null
    private var expectedReturn: Departure? = null
    private var pendingReturnId: String? = null

    /** Set by [SwipeDeck] on every composition. */
    internal var onCommit: (DeckItem, Boolean) -> Unit = { _, _ -> }
    internal var top: DeckItem? = null

    private var density: Float = 1f
    private var deckWidth: Float = 0f
    private var deckHeight: Float = 0f

    /** Where a level card is aimed when it flies off (and where an undone profile flies back in from). */
    internal var flyDistancePx: Float = 1_800f
        private set

    /** Set by [SwipeDeck] whenever the deck is measured. */
    internal fun setGeometry(width: Float, height: Float, density: Float) {
        if (width == deckWidth && height == deckHeight && density == this.density) return
        deckWidth = width
        deckHeight = height
        this.density = density
        flyDistancePx = flyTarget(y = 0f)
    }

    /** How far right (or left) to aim a card flying off at vertical offset [y]. */
    private fun flyTarget(y: Float): Float =
        max(FlyOffDistance.value * density, FLY_OFF_OVERSHOOT * flyOffDistance(deckWidth, deckHeight, density, y))

    private fun hasLeftDeck(offset: Offset): Boolean = isOffDeck(offset, density, deckWidth, deckHeight)

    private data class Departure(val id: String, val liked: Boolean)

    fun isDeparting(id: String): Boolean = departing.any { it.id == id }

    /** The card's offset, created at rest — or off-screen when it's the profile an undo brings back. */
    fun motion(id: String, initial: Offset = Offset.Zero): Animatable<Offset, AnimationVector2D> =
        motions.getOrPut(id) {
            val returning = expectedReturn
            val start = if (returning != null && returning.id == id) {
                expectedReturn = null
                pendingReturnId = id
                Offset(if (returning.liked) flyDistancePx else -flyDistancePx, 0f)
            } else {
                initial
            }
            Animatable(start, Offset.VectorConverter)
        }

    /** True once for the card an undo just brought back (it then flies in). */
    fun consumePendingReturn(id: String): Boolean {
        if (pendingReturnId != id) return false
        pendingReturnId = null
        return true
    }

    /** The undo button: the next time the last swiped profile appears, fly it back in from its side. */
    fun expectReturnOfLastProfile() {
        expectedReturn = lastProfileDeparture
    }

    /**
     * Programmatic swipe (the expanded profile's like/pass, an ad's CTA): flies
     * the card off when it's the top card, otherwise just records it.
     */
    fun swipe(item: DeckItem, liked: Boolean) {
        if (top?.id == item.id) commit(item, liked) else onCommit(item, liked)
    }

    fun dragTo(id: String, offset: Offset) {
        if (isDeparting(id)) return
        scope.launch { motion(id).snapTo(offset) }
    }

    fun springBack(id: String) {
        if (isDeparting(id)) return
        scope.launch { motion(id).animateTo(Offset.Zero, SpringBackSpec) }
    }

    /** Swipes [item] off the deck: [velocity] (px/s) is the release speed of a drag, zero for buttons. */
    fun commit(item: DeckItem, liked: Boolean, velocity: Velocity = Velocity.Zero) {
        if (isDeparting(item.id)) return
        val motion = motion(item.id)
        departing.add(item)
        if (item is DeckItem.Profile) lastProfileDeparture = Departure(item.id, liked)
        onCommit(item, liked)

        scope.launch {
            val direction = if (liked) 1f else -1f
            val start = motion.value
            // A drag past the threshold that ends with a flick back still commits
            // (iOS decides on distance): don't let that flick pull the card the wrong way.
            val throwX = if (velocity.x * direction < 0f) 0f else velocity.x
            val throwVelocity = Offset(throwX, velocity.y)
            val reach = deckHeight / 3f
            val drift = (velocity.y * FLY_OFF_VELOCITY_PROJECTION).coerceIn(-reach, reach)
            val targetY = start.y + drift
            val target = Offset(direction * flyTarget(targetY), targetY)
            val flight = launch {
                if (throwVelocity == Offset.Zero) {
                    motion.animateTo(target, ButtonFlyOffSpec)
                } else {
                    motion.animateTo(target, DragFlyOffSpec, initialVelocity = throwVelocity)
                }
            }
            try {
                // Done as soon as it's off the deck — no need to wait for the spring to settle.
                withTimeoutOrNull(FLY_OFF_MAX_MILLIS) {
                    snapshotFlow { hasLeftDeck(motion.value) }.first { it }
                }
            } finally {
                flight.cancel()
                departing.removeAll { it.id == item.id }
                motions.remove(item.id)
            }
        }
    }

    /** Forget motion for cards that are no longer on screen. */
    internal fun retain(ids: Set<String>) {
        motions.keys.retainAll(ids)
    }
}

@Composable
internal fun rememberDeckState(): DeckState {
    val scope = rememberCoroutineScope()
    return remember(scope) { DeckState(scope) }
}

/**
 * Tinder-style deck: the card fills the available space edge-to-edge and
 * the pass/like buttons float over its bottom instead of sitting below.
 * Only the top card drags; the cards behind scale up as it leaves.
 *
 * [card] renders one deck card; `isTop` is true for the draggable top card
 * (and for cards flying off, so their CTA doesn't vanish mid-flight).
 */
@Composable
internal fun SwipeDeck(
    deck: List<DeckItem>,
    deckState: DeckState,
    canUndo: Boolean,
    onSwipe: (DeckItem, liked: Boolean) -> Unit,
    onUndo: () -> Unit,
    onShare: (ShareableContent) -> Unit,
    modifier: Modifier = Modifier,
    previewDrag: DpOffset? = null,
    card: @Composable (item: DeckItem, isTop: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val departing = deckState.departing
    val departingIds = departing.mapTo(HashSet()) { it.id }
    val remaining = deck.filter { it.id !in departingIds }
    val visible = remaining.take(VISIBLE_CARDS)
    val top = visible.firstOrNull()

    val thresholdPx = with(density) { SwipeThreshold.toPx() }
    val previewPx = previewDrag?.let { with(density) { Offset(it.x.toPx(), it.y.toPx()) } }
    val topMotion = top?.let { deckState.motion(it.id, previewPx ?: Offset.Zero) }
    // How far the top card is toward committing (0…1): the cards behind follow it up.
    val topProgress by remember(topMotion) {
        derivedStateOf { topMotion?.let { (abs(it.value.x) / thresholdPx).coerceIn(0f, 1f) } ?: 0f }
    }

    SideEffect {
        deckState.onCommit = onSwipe
        deckState.top = top
        deckState.retain(visible.mapTo(HashSet()) { it.id } + departingIds)
    }

    // Undo: the re-inserted profile flies back in from the side it left.
    LaunchedEffect(top?.id) {
        val id = top?.id ?: return@LaunchedEffect
        if (deckState.consumePendingReturn(id)) deckState.motion(id).animateTo(Offset.Zero, ReturnSpec)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        SideEffect { deckState.setGeometry(width, height, density.density) }

        // The next cards' photos load (invisibly) before they join the stack, so
        // fast swiping doesn't outrun the images.
        DeckPrefetch(remaining.drop(VISIBLE_CARDS).take(PREFETCH_CARDS))

        // Top 3 cards, the bottom of the stack first; swiped cards still flying
        // off render above the top one. One keyed loop, so the top card keeps
        // its composition (photo page, motion) as it turns into a departing one.
        val stack = buildList {
            for (index in visible.indices.reversed()) add(visible[index] to index)
            departing.forEach { add(it to DEPARTING) }
        }
        for ((item, index) in stack) {
            key(item.id) {
                DeckCardSlot(
                    item = item,
                    index = index,
                    deckState = deckState,
                    topProgress = { topProgress },
                    thresholdPx = thresholdPx,
                    card = card,
                )
            }
        }

        SwipeActionButtons(
            onPass = { top?.let { deckState.commit(it, liked = false) } },
            onLike = { top?.let { deckState.commit(it, liked = true) } },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            onUndo = {
                deckState.expectReturnOfLastProfile()
                onUndo()
            },
            undoDisabled = !canUndo,
            onShare = { top?.shareContent?.let(onShare) },
            shareDisabled = top?.shareContent == null,
        )
    }
}

private const val DEPARTING = -1

/** How many cards past the visible three get their lead photo prefetched. */
private const val PREFETCH_CARDS = 2

/**
 * Composes the lead photo of each upcoming card at its on-card geometry with
 * zero alpha — the same RemotePhotoView pipeline and cache keys as the card,
 * so it's already decoded when the card surfaces.
 */
@Composable
private fun DeckPrefetch(items: List<DeckItem>) {
    for (item in items) {
        key("prefetch-${item.id}") {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0f }
                    .clearAndSetSemantics {},
            ) {
                when (item) {
                    is DeckItem.Profile -> item.card.photos?.firstOrNull()?.let {
                        RemotePhotoView(photo = it, modifier = Modifier.fillMaxSize())
                    }
                    is DeckItem.Ad -> item.ad.displayPhotos.firstOrNull()?.let { PhotoBand(it, Modifier.fillMaxWidth()) }
                    is DeckItem.News -> item.item.displayPhotos.firstOrNull()?.let { PhotoBand(it, Modifier.fillMaxWidth()) }
                    is DeckItem.Post -> item.post.displayPhotos.firstOrNull()?.let { PhotoBand(it, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun DeckCardSlot(
    item: DeckItem,
    index: Int,
    deckState: DeckState,
    topProgress: () -> Float,
    thresholdPx: Float,
    card: @Composable (item: DeckItem, isTop: Boolean) -> Unit,
) {
    val isTop = index == 0
    val isDeparting = index == DEPARTING
    val motion = deckState.motion(item.id)
    // Follow-the-target spring: continuous whether the stack shifts because the
    // top card was dragged, flew off, came back (undo) or was removed (report/block).
    val depthTarget = if (index >= 1) index - topProgress() else 0f
    val depth by animateFloatAsState(targetValue = depthTarget, animationSpec = DepthSpec, label = "deckDepth")
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 180)) }

    val currentItem by rememberUpdatedState(item)
    val depthOffsetPx = with(LocalDensity.current) { DepthOffsetStep.toPx() }
    val maxExclusionPx = with(LocalDensity.current) { MaxGestureExclusion.toPx() }

    val dragModifier = if (isTop) {
        Modifier
            // The card runs edge to edge: keep a horizontal drag that starts in a
            // screen-edge zone a card swipe instead of the system back gesture
            // (iOS has no back gesture at a tab root). Android honours at most
            // 200dp per edge, so take a band across the middle of the card.
            .systemGestureExclusion { coordinates ->
                val w = coordinates.size.width.toFloat()
                val h = coordinates.size.height.toFloat()
                val band = min(maxExclusionPx, h * 0.4f)
                val top = (h - band) / 2f
                Rect(0f, top, w, top + band)
            }
            // Outside graphicsLayer, so pointer positions (and the velocity tracker)
            // stay in the card's resting coordinates while it moves.
            .pointerInput(item.id) {
                val tracker = VelocityTracker()
                var dragOffset = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        // Grabbing a card mid spring-back continues from where it is.
                        dragOffset = deckState.motion(item.id).value
                    },
                    onDragEnd = {
                        when (val liked = swipeDecision(dragOffset.x, thresholdPx)) {
                            null -> deckState.springBack(item.id)
                            // The release speed only carries the fly-off animation.
                            else -> deckState.commit(currentItem, liked, tracker.calculateVelocity())
                        }
                    },
                    onDragCancel = { deckState.springBack(item.id) },
                    onDrag = { change, amount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragOffset += amount
                        deckState.dragTo(item.id, dragOffset)
                    },
                )
            }
    } else {
        Modifier
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(dragModifier)
            // Screen readers see only the top card (pass/like are the buttons).
            .then(if (isTop) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // iOS `.offset(offset).rotationEffect(offset.width / 18)`: the
                    // card turns about its resting centre, so it swings on an arc.
                    val offset = motion.value
                    val rotation = cardRotation(offset.x, density)
                    val shift = arcTranslation(offset, rotation)
                    translationX = shift.x
                    translationY = shift.y + depth * depthOffsetPx
                    rotationZ = rotation
                    val scale = depthScale(depth)
                    scaleX = scale
                    scaleY = scale
                    // A card entering third from the back fades in rather than popping.
                    alpha = appear.value * (VISIBLE_CARDS - depth).coerceIn(0f, 1f)
                },
        ) {
            card(item, isTop || isDeparting)
            if (!isTop) {
                // Cards behind the top one, and cards flying off, take no taps.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                )
            }
        }
        // iOS adds the stamps with `.overlay` *after* `.offset`/`.rotationEffect`. Those are
        // render-only effects that leave the layout frame in place, so the stamps stay at the
        // card's resting corners while it moves. A swiped card leaves the iOS deck at once, so
        // its stamp fades out with the removal while the card slides away: same here once the
        // card is departing. Only the top (depth 0) card ever shows a stamp.
        if (isTop || isDeparting) {
            Box(Modifier.fillMaxSize()) {
                SwipeStamps(likeLabel = item.likeLabel, offsetX = { motion.value.x }, enabled = isTop)
            }
        }
    }
}

/** LIKE (or VISIT / SAVE / JOIN) top-left and PASS top-right, shown past ±40dp of drag. */
@Composable
private fun BoxScope.SwipeStamps(likeLabel: String, offsetX: () -> Float, enabled: Boolean = true) {
    val colors = DrokpoTheme.colors
    Stamp(likeLabel, colors.brandRed, rotation = -15f, direction = 1f, offsetX = offsetX, enabled = enabled, modifier = Modifier.align(Alignment.TopStart))
    Stamp("PASS", colors.accent, rotation = 15f, direction = -1f, offsetX = offsetX, enabled = enabled, modifier = Modifier.align(Alignment.TopEnd))
}

/**
 * iOS `.opacity(visible ? 1 : 0)` under the deck's `.spring(duration: 0.3)`:
 * fully opaque once past the 40dp mark, eased in (and out) over time rather
 * than with distance.
 */
@Composable
private fun Stamp(
    text: String,
    color: Color,
    rotation: Float,
    direction: Float,
    offsetX: () -> Float,
    enabled: Boolean,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val currentOffsetX by rememberUpdatedState(offsetX)
    val currentEnabled by rememberUpdatedState(enabled)
    val visible by remember(direction, density) {
        derivedStateOf { currentEnabled && stampVisible(direction * currentOffsetX() / density.density) }
    }
    val opacity by animateFloatAsState(targetValue = if (visible) 1f else 0f, animationSpec = StampFadeSpec, label = "stamp")
    Text(
        text = text,
        style = DrokpoTheme.typography.title.bold(),
        color = color,
        modifier = modifier
            .padding(24.dp)
            .graphicsLayer {
                alpha = opacity
                rotationZ = rotation
            }
            .border(width = 3.dp, color = color, shape = RoundedCornerShape(8.dp))
            .padding(8.dp),
    )
}
