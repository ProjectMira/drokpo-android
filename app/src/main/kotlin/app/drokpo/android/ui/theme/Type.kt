package app.drokpo.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * iOS Dynamic Type text styles at their default ("Large") size, so a SwiftUI
 * `.font(.subheadline)` ports as `DrokpoTheme.typography.subheadline`. Sizes
 * and line heights are Apple's; the face is the platform default (Roboto).
 * Apple's per-size SF tracking is not carried over — it reads cramped on
 * Roboto.
 *
 * SwiftUI weight modifiers map to the helpers below:
 * `.font(.subheadline.bold())` → `typography.subheadline.bold()`.
 */
@Immutable
data class DrokpoTypography(
    /** 34 / bold — sign-in wordmark, "It's a match!", large nav titles. */
    val largeTitle: TextStyle,
    /** 28 — person-card name (`.title.bold()`). */
    val title: TextStyle,
    /** 22 — card age, news title (`.title2.bold()`), ContentUnavailableView title. */
    val title2: TextStyle,
    /** 20. */
    val title3: TextStyle,
    /** 17 / semibold — row titles, empty-state titles, inline nav titles. */
    val headline: TextStyle,
    /** 17 — default text. */
    val body: TextStyle,
    /** 16. */
    val callout: TextStyle,
    /** 15 — secondary copy, filter pills. */
    val subheadline: TextStyle,
    /** 13 — section headers/footers, tag chips. */
    val footnote: TextStyle,
    /** 12 — timestamps, labels on prompt answers. */
    val caption: TextStyle,
    /** 11 — uppercase source names, tiny capsules. */
    val caption2: TextStyle,
)

private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

internal val DefaultDrokpoTypography = DrokpoTypography(
    largeTitle = style(34, 41, FontWeight.Bold),
    title = style(28, 34),
    title2 = style(22, 28),
    title3 = style(20, 25),
    headline = style(17, 22, FontWeight.SemiBold),
    body = style(17, 22),
    callout = style(16, 21),
    subheadline = style(15, 20),
    footnote = style(13, 18),
    caption = style(12, 16),
    caption2 = style(11, 13),
)

/**
 * The same scale mapped onto Material roles, so stock components match iOS:
 * - `headlineLarge/Medium` = largeTitle (LargeTopAppBar's expanded title)
 * - `titleLarge` = headline (TopAppBar / CenterAlignedTopAppBar = iOS inline title)
 * - `bodyLarge` = body (default `Text`, TextField), `bodyMedium` = subheadline,
 *   `bodySmall` = footnote
 * - `labelLarge` = body (stock buttons, menu items — iOS buttons/menus are 17 regular),
 *   `labelMedium/Small` = tab-bar sized labels
 */
internal fun DrokpoTypography.toMaterialTypography(): Typography = Typography(
    displayLarge = largeTitle,
    displayMedium = largeTitle,
    displaySmall = title,
    headlineLarge = largeTitle,
    headlineMedium = largeTitle,
    headlineSmall = title2,
    titleLarge = headline,
    titleMedium = headline,
    titleSmall = subheadline.semibold(),
    bodyLarge = body,
    bodyMedium = subheadline,
    bodySmall = footnote,
    labelLarge = body,
    labelMedium = caption.copy(fontWeight = FontWeight.Medium),
    labelSmall = caption2.copy(fontWeight = FontWeight.Medium),
)

/** SwiftUI `.bold()`. */
fun TextStyle.bold(): TextStyle = copy(fontWeight = FontWeight.Bold)

/** SwiftUI `.weight(.semibold)`. */
fun TextStyle.semibold(): TextStyle = copy(fontWeight = FontWeight.SemiBold)

/** SwiftUI `.weight(.medium)`. */
fun TextStyle.medium(): TextStyle = copy(fontWeight = FontWeight.Medium)

/** SwiftUI `.monospacedDigit()` — tabular figures (counters, OTP field). */
fun TextStyle.monospacedDigit(): TextStyle = copy(fontFeatureSettings = "tnum")
