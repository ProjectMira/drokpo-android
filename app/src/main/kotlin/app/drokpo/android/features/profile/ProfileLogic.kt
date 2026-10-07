package app.drokpo.android.features.profile

import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Socials
import app.drokpo.android.core.model.Vocabulary
import kotlin.math.abs

// Pure helpers behind ProfileScreen / ProfileModel — kept free of Android and
// Compose so the reorder and display rules are unit-testable on the JVM.

/** The backend (and iOS) cap a profile at six photos; the "+" tile hides at the cap. */
internal const val MAX_PROFILE_PHOTOS = 6

internal object ProfileCopy {
    const val PHOTOS_FOOTER = "Drag to reorder — your first photo is the one people see on your card."
    const val NO_PROMPTS = "Answer a few prompts in Edit — they're the easiest way to start conversations."
    const val DISCOVERABLE_FOOTER = "Your profile can appear in other people's swipe deck."
    const val HIDDEN_FOOTER =
        "Your profile is hidden — nobody can find you by swiping. Your matches and chats keep working."
    const val ACCOUNT_FOOTER = "The account you're signed in with. Only you can see this."
}

/**
 * iOS `PhotoDropDelegate.dropEntered`: moves the dragged photo onto the slot of
 * the photo it is hovering over (`move(fromOffsets: from, toOffset: to > from ?
 * to + 1 : to)`, i.e. the dragged photo ends up at the target's index and the
 * photos in between shift by one). Unknown ids or dragging onto itself leave
 * the list unchanged.
 */
internal fun movePhoto(photos: List<Photo>, draggedId: String, targetId: String): List<Photo> {
    if (draggedId == targetId) return photos
    val from = photos.indexOfFirst { it.id == draggedId }
    val to = photos.indexOfFirst { it.id == targetId }
    if (from < 0 || to < 0) return photos
    val moved = photos.toMutableList()
    val photo = moved.removeAt(from)
    moved.add(to, photo)
    return moved
}

/** Storage paths in display order — the body of PATCH /api/profile/me/photos/order. */
internal fun List<Photo>.storagePaths(): List<String> = map { it.storagePath }

/** iOS `promptsSection`: answered prompts in `Vocabulary.questions` order, empty answers skipped. */
internal fun answeredPrompts(answers: Map<String, String>?): List<Pair<String, String>> =
    Vocabulary.questions.mapNotNull { question ->
        val answer = answers?.get(question.key)
        if (answer.isNullOrEmpty()) null else question.label to answer
    }

/** "@handle" for a non-empty handle, else null (the row then shows "—"). */
internal fun atHandle(handle: String?): String? = handle?.takeIf { it.isNotEmpty() }?.let { "@$it" }

/** iOS `socialsSection` rows: Instagram always (value or "—"); YouTube and TikTok only when set. */
internal fun socialRows(socials: Socials?): List<Pair<String, String?>> = buildList {
    add("Instagram" to atHandle(socials?.instagram))
    socials?.youtube?.takeIf { it.isNotEmpty() }?.let { add("YouTube" to it) }
    socials?.tiktok?.takeIf { it.isNotEmpty() }?.let { add("TikTok" to "@$it") }
}

internal fun discoveryFooter(isDiscoverable: Boolean): String =
    if (isDiscoverable) ProfileCopy.DISCOVERABLE_FOOTER else ProfileCopy.HIDDEN_FOOTER

/** iOS `row(_:_:)` values: `languages?.joined(separator: ", ")` — an empty list joins to "". */
internal fun List<String>?.joinedOrNull(): String? = this?.joinToString(", ")

// region Photo strip drag gestures (PhotoStrip's PhotoDragState delegates here)

/**
 * One laid-out tile of the photo strip: its lazy key and index, and where it sits along the
 * row in viewport pixels (LazyListItemInfo's offset/size, copied so this stays Compose-free).
 */
internal data class StripTile(val key: Any, val index: Int, val start: Int, val size: Int)

/**
 * The photo tile spanning [x] (viewport pixels along the row): never the "+" tile
 * ([addTileKey]) or [except] (the tile being dragged). Null over the gap between tiles —
 * iOS only attaches `.onDrag`/`.onDrop` to photo cells.
 */
internal fun photoTileAt(x: Float, tiles: List<StripTile>, addTileKey: Any, except: Any? = null): StripTile? =
    tiles.firstOrNull { it.key != addTileKey && it.key != except && x >= it.start && x <= it.start + it.size }

/**
 * Translation that keeps a dragged tile under the finger: it was picked up with its leading
 * edge at [pickUpStart], the finger has moved [distance] since, and the strip now lays the
 * tile out at [laidOutStart] (a new slot after a swap, or a new place after auto-scroll).
 */
internal fun dragTranslation(pickUpStart: Int, distance: Float, laidOutStart: Int): Float =
    pickUpStart + distance - laidOutStart

/** Fraction of the overshoot scrolled per frame while a tile is held past an edge. */
internal const val AUTO_SCROLL_GAIN = 0.25f

/**
 * Auto-scroll step for one frame while a dragged tile whose leading edge is at [start]
 * (viewport pixels; the strip has no main-axis padding, so the viewport starts at 0) is
 * pushed past either edge: negative past the leading edge, positive past the trailing one
 * ([viewportEnd]), a quarter of the overshoot, clamped to ±[maxStep]; under half a pixel
 * it is 0 so the loop goes quiet instead of creeping.
 */
internal fun autoScrollStep(start: Float, size: Int, viewportEnd: Float, maxStep: Float): Float {
    val end = start + size
    val step = when {
        start < 0f -> start * AUTO_SCROLL_GAIN
        end > viewportEnd -> (end - viewportEnd) * AUTO_SCROLL_GAIN
        else -> 0f
    }
    return if (abs(step) < 0.5f) 0f else step.coerceIn(-maxStep, maxStep)
}

/**
 * Ping-pong guard for drag-to-reorder. After a move is requested with the dragged tile at
 * index N, the strip still shows the old layout for a frame or two; checking for a swap
 * against those stale positions would request the inverse move, and the tiles would swap
 * back and forth. So swap checks are [blocks]ed until the dragged tile is laid out at a
 * different index — with a release valve after [maxChecks] checks in case the move was
 * refused (e.g. the model ignored it) and the index never changes.
 * iOS `dropEntered` fires once per entered target, so it never needs this.
 */
internal class LayoutGate(private val maxChecks: Int = 30) {
    private var awaitingIndex: Int? = null
    private var checks = 0

    /** A move was just requested while the dragged tile was laid out at [index]. */
    fun arm(index: Int) {
        awaitingIndex = index
        checks = 0
    }

    fun reset() {
        awaitingIndex = null
        checks = 0
    }

    /** Should this swap check be skipped? [currentIndex] is the dragged tile's laid-out index now. */
    fun blocks(currentIndex: Int): Boolean {
        val awaited = awaitingIndex ?: return false
        if (currentIndex != awaited || ++checks > maxChecks) {
            reset()
            return false
        }
        return true
    }
}

// endregion
