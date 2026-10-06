package app.drokpo.android.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.drokpo.android.R

/**
 * Drokpo's brand palette (port of Core/Brand.swift): blue (`accent`, the app's
 * tint) is primary — buttons, links, chat bubbles, selected tabs. Red
 * (`brandRed`) is reserved for like/love actions — hearts, the like button,
 * the LIKE stamp — and is also the brand ground behind the blue handshake on
 * the app icon and the sign-in logo (see ci/make_brand_assets.py).
 *
 * In UI use the theme-aware `DrokpoTheme.colors.accent` / `.brandRed`; these
 * fixed values are for brand artwork that must not shift in dark mode.
 */
object Brand {
    /** #FF0000 — YouTube red, the logo / launcher-icon ground. */
    val Red = Color(0xFFFF0000)

    /** #1877F2 — Facebook blue, the handshake and the light-mode accent. */
    val Blue = Color(0xFF1877F2)
}

/**
 * The fades the Discover cards lay over their photos so white text (and the
 * deck's overlaid pass/like buttons) always sits on dark.
 */
object PhotoScrims {
    /**
     * Person card (CardView): fade reaches from mid-card past the info block
     * to the bottom, so the text and the deck's overlaid pass/like buttons
     * both sit on dark.
     */
    val personCard: Brush = Brush.verticalGradient(
        0.40f to Color.Transparent,
        0.62f to Color.Black.copy(alpha = 0.45f),
        1.0f to Color.Black.copy(alpha = 0.88f),
    )

    /**
     * News / ad / community-post cards: the fade reaches from mid-card down
     * past the text to the buttons, so everything written sits on dark.
     */
    val contentCard: Brush = Brush.verticalGradient(
        0.30f to Color.Transparent,
        0.55f to Color.Black.copy(alpha = 0.55f),
        1.0f to Color.Black.copy(alpha = 0.94f),
    )

    /**
     * Brand gradient a news/post card shows when the story has no image at
     * all: accent → brandRed, top-leading to bottom-trailing.
     */
    val noPhotoFallback: Brush
        @Composable @ReadOnlyComposable
        get() = Brush.linearGradient(
            listOf(
                DrokpoTheme.colors.accent.copy(alpha = 0.85f),
                DrokpoTheme.colors.brandRed.copy(alpha = 0.75f),
            ),
        )
}

/**
 * The in-app logo (iOS `Image("Logo")`): red rounded tile with the blue
 * handshake, generated identically to the iOS Logo.png. Sign-in shows it at 96.
 */
@Composable
fun DrokpoLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    Image(
        painter = painterResource(R.drawable.logo),
        contentDescription = "Drokpo",
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}

@DrokpoPreviews
@Composable
private fun BrandPreview() {
    DrokpoTheme {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DrokpoLogo()
            Text("Drokpo", style = DrokpoTheme.typography.largeTitle, color = DrokpoTheme.colors.label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("accent", color = DrokpoTheme.colors.accent)
                Text("brandRed", color = DrokpoTheme.colors.brandRed)
                Text("secondary", color = DrokpoTheme.colors.secondaryLabel)
            }
        }
    }
}
