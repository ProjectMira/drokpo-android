package app.drokpo.android.features.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.PhotoBand
import app.drokpo.android.core.model.Photo
import app.drokpo.android.ui.components.OverlayTag
import app.drokpo.android.ui.components.ProminentButton
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.PhotoScrims
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Corner radius every deck card is clipped to (iOS `RoundedRectangle(cornerRadius: 20)`). */
internal val DeckCardShape: Shape = RoundedCornerShape(20.dp)

/** Space every content card keeps above its photo band for the badge / top-right affordance. */
private val BadgeRowClearance = 52.dp

/**
 * The rounded, shadowed frame every deck card draws in (`.clipShape(RoundedRectangle(20))
 * .shadow(radius: 6, y: 3)`). [onTap] (top card only) makes the whole card a tap target that
 * yields to any button inside it.
 */
@Composable
internal fun DeckCardSurface(
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    tapLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val tapModifier = if (onTap != null) {
        Modifier
            .pointerInput(Unit) { detectTapGestures { currentOnTap?.invoke() } }
            .semantics { onClick(label = tapLabel) { currentOnTap?.invoke(); true } }
    } else {
        Modifier
    }
    Box(
        modifier
            .fillMaxSize()
            .shadow(elevation = 6.dp, shape = DeckCardShape, clip = false)
            .clip(DeckCardShape)
            .then(tapModifier),
        content = content,
    )
}

/**
 * The banded layout the news, ad and community-post cards share. Their
 * images are almost always landscape, so instead of force-cropping them
 * full-bleed the card shows the photo as a full-width 16:9 band on a dark
 * backdrop, with a fade rising from the bottom that carries the text — and,
 * above the reserved clearance, the deck's overlaid pass/like buttons — in
 * clear contrast. The brand gradient fills in when there is no image at all.
 */
@Composable
internal fun ContentCardLayout(
    photo: Photo?,
    badge: String,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    tapLabel: String? = null,
    topEnd: (@Composable BoxScope.() -> Unit)? = null,
    text: @Composable ColumnScope.() -> Unit,
) {
    val colors = DrokpoTheme.colors
    DeckCardSurface(modifier = modifier, onTap = onTap, tapLabel = tapLabel) {
        // Dark backdrop lets a landscape photo letterbox cleanly; the brand
        // gradient fills in when the story has no image at all.
        if (photo == null) {
            Box(Modifier.matchParentSize().background(PhotoScrims.noPhotoFallback))
        } else {
            Box(Modifier.matchParentSize().background(colors.cardBackdrop))
            PhotoBand(
                photo = photo,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = BadgeRowClearance) // clear of the badge / top-right row
                    .fillMaxWidth(),
            )
        }

        // The fade reaches from mid-card down past the text to the buttons,
        // so everything written sits on dark.
        Box(Modifier.matchParentSize().background(PhotoScrims.contentCard))

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // Keep every word above the overlaid pass/like buttons.
                .padding(bottom = SwipeActionButtonsDefaults.DeckClearance),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = text,
        )

        OverlayTag(badge, Modifier.align(Alignment.TopStart).padding(12.dp))
        topEnd?.invoke(this)
    }
}

/** White card text at an iOS `.opacity(alpha)`. */
@Composable
internal fun CardText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = style,
        color = DrokpoTheme.colors.onPhoto.copy(alpha = alpha),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** iOS `Label(text, systemImage:)` in white on a card. */
@Composable
internal fun CardLabel(
    text: String,
    icon: ImageVector,
    style: TextStyle,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    iconSize: Dp = 16.dp,
) {
    val tint = DrokpoTheme.colors.onPhoto.copy(alpha = alpha)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        Text(text, style = style, color = tint)
    }
}

/** The ad / link-post call to action: a 44dp full-width `.borderedProminent` button. */
@Composable
internal fun CardCallToAction(label: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ProminentButton(
        text = label?.takeIf { it.isNotEmpty() } ?: "Learn more",
        onClick = onClick,
        modifier = modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .height(44.dp),
        textStyle = DrokpoTheme.typography.headline,
    )
}

/** Shared card colours that never follow the theme (they always sit on a photo/scrim). */
internal object DeckCardColors {
    val progressInactive: Color = Color.White.copy(alpha = 0.35f)
}

/**
 * `date.formatted(date: .abbreviated, time: .shortened)` — "Oct 12, 2026 at 6:00 PM" in
 * English; other locales get their own medium date + short time.
 */
internal fun formatEventDate(
    instant: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val dateTime = instant.atZone(zone)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(dateTime)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(dateTime)
    return if (locale.language == Locale.ENGLISH.language) "$date at $time" else "$date, $time"
}
