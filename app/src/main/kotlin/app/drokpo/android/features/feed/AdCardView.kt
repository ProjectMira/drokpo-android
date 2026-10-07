package app.drokpo.android.features.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.core.model.AdCard
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * A sponsored card in the Discover deck — same banded layout as the other
 * content cards (ad creatives are typically landscape): photo band on a
 * dark backdrop, bottom fade carrying the pitch and CTA above the deck's
 * overlaid buttons, clearly labelled. (Port of iOS AdCardView.)
 *
 * [onOpen] opens the ad link (same as swiping right); null when not the top card.
 */
@Composable
internal fun AdCardView(
    ad: AdCard,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val typography = DrokpoTheme.typography
    ContentCardLayout(
        photo = ad.displayPhotos.firstOrNull(),
        badge = "Sponsored",
        modifier = modifier,
    ) {
        CardText(ad.title ?: "—", style = typography.title2.bold(), maxLines = 3)
        ad.body?.takeIf { it.isNotEmpty() }?.let { body ->
            CardText(body, style = typography.subheadline, alpha = 0.95f, maxLines = 3)
        }
        if (onOpen != null) {
            CardCallToAction(label = ad.ctaLabel, onClick = onOpen)
        }
    }
}
