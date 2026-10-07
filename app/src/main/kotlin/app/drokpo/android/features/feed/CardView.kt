package app.drokpo.android.features.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandCircleDown
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.PhotoScrims
import app.drokpo.android.ui.theme.bold
import kotlin.math.roundToInt

/**
 * A member's card in the Discover deck (port of iOS CardView): full-bleed
 * photo with tap zones to page through photos, progress capsules, and the
 * name / region / languages / bio block over a bottom fade.
 *
 * [onExpand] is null unless this is the top card. [prefetchNextPhoto]
 * composes the next photo invisibly so paging to it is instant.
 */
@Composable
internal fun CardView(
    card: FeedCard,
    onSafetyTapped: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    prefetchNextPhoto: Boolean = onExpand != null,
) {
    val typography = DrokpoTheme.typography
    val photos = card.photos.orEmpty()
    var photoIndex by remember(card.uid) { mutableIntStateOf(0) }
    val shownIndex = photoIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    val currentOnExpand by rememberUpdatedState(onExpand)

    DeckCardSurface(modifier) {
        Box(Modifier.fillMaxSize()) {
            if (photos.isEmpty()) {
                RemotePhotoView(photo = null, modifier = Modifier.fillMaxSize())
            } else {
                // The next photo sits (invisible) under the current one so it's
                // already loaded when the right zone is tapped.
                val next = (shownIndex + 1).takeIf { prefetchNextPhoto && it < photos.size }
                for (index in listOfNotNull(next, shownIndex)) {
                    key(index) {
                        RemotePhotoView(
                            photo = photos[index],
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = if (index == shownIndex) 1f else 0f },
                            contentDescription = if (index == shownIndex) card.displayName else null,
                        )
                    }
                }
            }
        }

        // Tap the outer thirds to flip through photos; the center column
        // expands the profile, same as tapping the info block. A sibling layer
        // above the photos (iOS: tap-zone Rectangles over RemotePhotoView in the
        // ZStack), so a failed photo's tap-to-retry — the shown one, or the
        // invisible prefetched next one, which alpha doesn't hide from hit
        // testing — can't swallow the taps.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(card.uid, photos.size) {
                    detectTapGestures { position ->
                        if (photos.size > 1) {
                            val zone = size.width * 0.3f
                            when {
                                position.x < zone -> photoIndex = (photoIndex - 1).coerceAtLeast(0)
                                position.x > size.width - zone -> photoIndex = (photoIndex + 1).coerceAtMost(photos.size - 1)
                                else -> currentOnExpand?.invoke()
                            }
                        } else {
                            currentOnExpand?.invoke()
                        }
                    }
                },
        )

        // Fade reaches from mid-card past the info block to the bottom, so
        // the text and the deck's overlaid pass/like buttons both sit on dark.
        Box(Modifier.matchParentSize().background(PhotoScrims.personCard))

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { currentOnExpand?.invoke() } }
                .semantics(mergeDescendants = false) {
                    if (onExpand != null) onClick(label = "Show profile") { currentOnExpand?.invoke(); true }
                }
                .padding(horizontal = 16.dp)
                // Keep the name/bio above the overlaid pass/like buttons.
                .padding(bottom = SwipeActionButtonsDefaults.DeckClearance),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (photos.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    photos.indices.forEach { index ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(if (index == shownIndex) Color.White else DeckCardColors.progressInactive),
                        )
                    }
                }
            }

            // HStack(alignment: .firstTextBaseline): name, age, expand chevron, "…".
            Row {
                Row(
                    Modifier
                        .weight(1f)
                        .alignByBaseline(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CardText(
                        text = card.displayName ?: "—",
                        style = typography.title.bold(),
                        modifier = Modifier
                            .alignByBaseline()
                            .weight(1f, fill = false),
                        maxLines = 2,
                    )
                    card.displayAge?.let { age ->
                        CardText("$age", style = typography.title2, modifier = Modifier.alignByBaseline())
                    }
                    if (onExpand != null) {
                        Icon(
                            Icons.Filled.ExpandCircleDown,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier
                                // Sit the glyph on the text baseline like an SF Symbol.
                                .alignBy { (it.measuredHeight * 0.85f).roundToInt() }
                                .size(17.dp)
                                .rotate(180f),
                        )
                    }
                }
                SafetyButton(
                    onClick = onSafetyTapped,
                    // Sit the disc on the name's first baseline like an SF Symbol.
                    modifier = Modifier.alignBy { (it.measuredHeight * 0.85f).roundToInt() },
                )
            }

            card.region?.let { region ->
                CardLabel(region, icon = Icons.Filled.Place, style = typography.subheadline)
            }
            card.languages?.takeIf { it.isNotEmpty() }?.let { languages ->
                CardText(languages.joinToString(" · "), style = typography.footnote, alpha = 0.9f)
            }
            card.bio?.takeIf { it.isNotEmpty() }?.let { bio ->
                CardText(bio, style = typography.footnote, alpha = 0.9f, maxLines = 2)
            }
        }
    }
}

/**
 * `ellipsis.circle.fill` at `.title2` in white 90 %: a white disc with the
 * three dots knocked out, so the photo shows through them. Drawn at its
 * 26dp glyph size so it doesn't make the name row taller; Compose's hit
 * testing still extends the touch target to the 48dp minimum.
 */
@Composable
private fun SafetyButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Report or block", onClick = onClick)
            .semantics { contentDescription = "More" }
            // Offscreen, so BlendMode.Clear punches through the disc only.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        drawCircle(Color.White.copy(alpha = 0.9f))
        val dotRadius = size.minDimension * 0.065f
        val spacing = size.minDimension * 0.25f
        for (i in -1..1) {
            drawCircle(
                color = Color.Black,
                radius = dotRadius,
                center = center + Offset(i * spacing, 0f),
                blendMode = BlendMode.Clear,
            )
        }
    }
}
