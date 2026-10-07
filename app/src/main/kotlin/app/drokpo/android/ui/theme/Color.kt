package app.drokpo.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Drokpo's colour tokens: the iOS asset catalog (AccentColor, BrandRed) plus
 * the iOS system colours the SwiftUI screens lean on implicitly (grouped
 * backgrounds, label hierarchy, separators, `.quaternary` fills, status
 * colours). Read them through `DrokpoTheme.colors`; Material components get
 * the same values through `MaterialTheme.colorScheme` (see [drokpoColorScheme]).
 *
 * Brand rule (Brand.swift): blue `accent` is primary — buttons, links, chat
 * bubbles, selected tabs, toggles. Red `brandRed` is reserved for like/love
 * actions — hearts, the like button, the LIKE stamp. Destructive actions use
 * the iOS system red ([destructive]), not the brand red.
 */
@Immutable
data class DrokpoColors(
    val isDark: Boolean,

    // Brand
    /** App tint (iOS AccentColor): #1877F2 / #4599FF. */
    val accent: Color,
    /** Text/icons on an [accent] fill. */
    val onAccent: Color,
    /** Like/love only (iOS BrandRed): #FF0000 / #FF453A. */
    val brandRed: Color,
    /** iOS systemRed — `role: .destructive` buttons, delete/report/block. */
    val destructive: Color,

    // Labels (iOS label hierarchy; translucent like UIKit's)
    /** `.primary` text. */
    val label: Color,
    /** `.secondary` text and icons. */
    val secondaryLabel: Color,
    /** `.tertiary` — disclosure chevrons, disabled text. */
    val tertiaryLabel: Color,
    val quaternaryLabel: Color,
    /** TextField placeholder. */
    val placeholderText: Color,

    // Backgrounds
    /** systemBackground — plain screens, the Discover deck, sheets. */
    val background: Color,
    /** secondarySystemBackground. */
    val secondaryBackground: Color,
    /** tertiarySystemBackground. */
    val tertiaryBackground: Color,
    /** systemGroupedBackground — behind `List`/`Form` (inset grouped). */
    val groupedBackground: Color,
    /** secondarySystemGroupedBackground — grouped rows / cards. */
    val secondaryGroupedBackground: Color,
    /** tertiarySystemGroupedBackground — content nested inside a grouped row. */
    val tertiaryGroupedBackground: Color,
    /** `.background(.bar)` — bottom action bars, tab bar. Slightly translucent. */
    val bar: Color,

    // Separators & fills
    /** Hairline row separators (translucent). */
    val separator: Color,
    /** Opaque separator / outlines (rounded-border text fields). */
    val opaqueSeparator: Color,
    /**
     * SwiftUI `.quaternary` used as a shape fill — photo placeholders, tag
     * capsules, incoming chat bubbles, empty photo slots, quick-action circles.
     */
    val fill: Color,
    /** `.quaternary.opacity(0.5)` — prompt answer cards, account-type tiles. */
    val fillSubtle: Color,
    /** tertiarySystemFill — segmented-control track, disabled button fill. */
    val tertiaryFill: Color,
    /**
     * secondarySystemFill — the grey behind an untinted `.buttonStyle(.bordered)`
     * (App Store sign-in screenshots: (233,233,235) light, (38,38,41) dark).
     */
    val secondaryFill: Color,

    // iOS system grays
    val systemGray: Color,
    val systemGray2: Color,
    val systemGray3: Color,
    val systemGray4: Color,
    val systemGray5: Color,
    /** `Color(.systemGray6)` — unselected filter pills. */
    val systemGray6: Color,

    // Status colours
    val green: Color,
    val orange: Color,
    val yellow: Color,

    // Photo-card overlays (theme-independent: they always sit on photos)
    /** Content drawn on top of photos / scrims. */
    val onPhoto: Color,
    /** `.black.opacity(0.45)` — the news card's open-arrow disc. */
    val photoScrimLight: Color,
    /** `.black.opacity(0.55)` — "News"/"Sponsored" capsules on cards. */
    val photoScrim: Color,
    /** `.black.opacity(0.6)` — "Primary" badge, xmark delete discs on photos. */
    val photoScrimStrong: Color,
    /** `.black.opacity(0.75)` — full-screen dimming (match overlay). */
    val dimmingScrim: Color,
    /** `Color(white: 0.07)` — dark backdrop a landscape news photo letterboxes on. */
    val cardBackdrop: Color,
)

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)

internal val LightDrokpoColors = DrokpoColors(
    isDark = false,
    accent = Color(0xFF1877F2),
    onAccent = White,
    brandRed = Color(0xFFFF0000),
    destructive = Color(0xFFFF3B30),
    label = Black,
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    quaternaryLabel = Color(0x2E3C3C43),
    placeholderText = Color(0x4D3C3C43),
    background = White,
    secondaryBackground = Color(0xFFF2F2F7),
    tertiaryBackground = White,
    groupedBackground = Color(0xFFF2F2F7),
    secondaryGroupedBackground = White,
    tertiaryGroupedBackground = Color(0xFFF2F2F7),
    bar = Color(0xF0F9F9F9),
    separator = Color(0x4A3C3C43),
    opaqueSeparator = Color(0xFFC6C6C8),
    fill = Color(0x2E3C3C43),
    fillSubtle = Color(0x173C3C43),
    tertiaryFill = Color(0x1F767680),
    secondaryFill = Color(0x29787880),
    systemGray = Color(0xFF8E8E93),
    systemGray2 = Color(0xFFAEAEB2),
    systemGray3 = Color(0xFFC7C7CC),
    systemGray4 = Color(0xFFD1D1D6),
    systemGray5 = Color(0xFFE5E5EA),
    systemGray6 = Color(0xFFF2F2F7),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    yellow = Color(0xFFFFCC00),
    onPhoto = White,
    photoScrimLight = Color(0x73000000),
    photoScrim = Color(0x8C000000),
    photoScrimStrong = Color(0x99000000),
    dimmingScrim = Color(0xBF000000),
    cardBackdrop = Color(0xFF121212),
)

internal val DarkDrokpoColors = DrokpoColors(
    isDark = true,
    accent = Color(0xFF4599FF),
    onAccent = White,
    brandRed = Color(0xFFFF453A),
    destructive = Color(0xFFFF453A),
    label = White,
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    quaternaryLabel = Color(0x29EBEBF5),
    placeholderText = Color(0x4DEBEBF5),
    background = Black,
    secondaryBackground = Color(0xFF1C1C1E),
    tertiaryBackground = Color(0xFF2C2C2E),
    groupedBackground = Black,
    secondaryGroupedBackground = Color(0xFF1C1C1E),
    tertiaryGroupedBackground = Color(0xFF2C2C2E),
    bar = Color(0xF01C1C1E),
    separator = Color(0x99545458),
    opaqueSeparator = Color(0xFF38383A),
    fill = Color(0x29EBEBF5),
    fillSubtle = Color(0x14EBEBF5),
    tertiaryFill = Color(0x3D767680),
    secondaryFill = Color(0x52787880),
    systemGray = Color(0xFF8E8E93),
    systemGray2 = Color(0xFF636366),
    systemGray3 = Color(0xFF48484A),
    systemGray4 = Color(0xFF3A3A3C),
    systemGray5 = Color(0xFF2C2C2E),
    systemGray6 = Color(0xFF1C1C1E),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    yellow = Color(0xFFFFD60A),
    onPhoto = White,
    photoScrimLight = Color(0x73000000),
    photoScrim = Color(0x8C000000),
    photoScrimStrong = Color(0x99000000),
    dimmingScrim = Color(0xBF000000),
    cardBackdrop = Color(0xFF121212),
)

/**
 * Material 3 scheme built from the same tokens, so stock Material components
 * (Switch, TextField, TopAppBar, NavigationBar, AlertDialog, ModalBottomSheet,
 * DropdownMenu, Snackbar…) pick up the iOS look without per-call colours.
 * `surfaceTint` is transparent: iOS has no tonal elevation tint.
 */
internal fun drokpoColorScheme(c: DrokpoColors): ColorScheme = if (c.isDark) {
    darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = Color(0xFF0F2238),
        onPrimaryContainer = Color(0xFFB9D9FF),
        inversePrimary = LightDrokpoColors.accent,
        secondary = c.accent,
        onSecondary = c.onAccent,
        secondaryContainer = Color(0xFF0F2238),
        onSecondaryContainer = c.accent,
        tertiary = c.green,
        onTertiary = Black,
        tertiaryContainer = Color(0xFF0B2E16),
        onTertiaryContainer = c.green,
        background = c.background,
        onBackground = c.label,
        surface = c.background,
        onSurface = c.label,
        surfaceVariant = c.systemGray6,
        onSurfaceVariant = c.secondaryLabel,
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFFF2F2F7),
        inverseOnSurface = Black,
        error = c.destructive,
        onError = White,
        errorContainer = Color(0xFF3B0D0A),
        onErrorContainer = Color(0xFFFFB4AE),
        outline = c.opaqueSeparator,
        outlineVariant = c.separator,
        scrim = Black,
        surfaceBright = Color(0xFF2C2C2E),
        surfaceDim = Black,
        surfaceContainerLowest = Black,
        surfaceContainerLow = Color(0xFF1C1C1E),
        surfaceContainer = Color(0xFF1C1C1E),
        surfaceContainerHigh = Color(0xFF2C2C2E),
        surfaceContainerHighest = Color(0xFF3A3A3C),
    )
} else {
    lightColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = Color(0xFFDCEBFD),
        onPrimaryContainer = Color(0xFF0A3D80),
        inversePrimary = DarkDrokpoColors.accent,
        secondary = c.accent,
        onSecondary = c.onAccent,
        secondaryContainer = Color(0xFFDCEBFD),
        onSecondaryContainer = c.accent,
        tertiary = c.green,
        onTertiary = White,
        tertiaryContainer = Color(0xFFD8F5DF),
        onTertiaryContainer = Color(0xFF0D5A22),
        background = c.background,
        onBackground = c.label,
        surface = c.background,
        onSurface = c.label,
        surfaceVariant = c.systemGray6,
        onSurfaceVariant = c.secondaryLabel,
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFF2C2C2E),
        inverseOnSurface = White,
        error = c.destructive,
        onError = White,
        errorContainer = Color(0xFFFFE5E3),
        onErrorContainer = Color(0xFF8A1A12),
        outline = c.opaqueSeparator,
        outlineVariant = c.separator,
        scrim = Black,
        surfaceBright = White,
        surfaceDim = Color(0xFFE5E5EA),
        surfaceContainerLowest = White,
        surfaceContainerLow = White,
        surfaceContainer = Color(0xFFF9F9F9),
        surfaceContainerHigh = Color(0xFFF2F2F7),
        surfaceContainerHighest = Color(0xFFE5E5EA),
    )
}
