package app.drokpo.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LocalDrokpoColors = staticCompositionLocalOf { LightDrokpoColors }
private val LocalDrokpoTypography = staticCompositionLocalOf { DefaultDrokpoTypography }

/**
 * Corner radii from the iOS screens: 6 (small controls), 10 (inset-grouped
 * sections, photo cells), 14 (tiles, large buttons), 20 (Discover cards).
 * ModalBottomSheet / AlertDialog use `extraLarge`.
 */
private val DrokpoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/**
 * Root theme. Brand colours only — dynamic (wallpaper) colour is deliberately
 * off so Drokpo looks the same on every device, like iOS.
 *
 * Callers resolve the user's Settings → Appearance choice (`AppearanceMode`
 * in core/AppPreferences.kt: System / Light / Dark) into the dark flag:
 * System → `isSystemInDarkTheme()`, Light → false, Dark → true. Pass it as
 * [darkTheme] or, equivalently, [dark] (the spelling CONTRACT.md §A.14 uses:
 * `DrokpoTheme(dark = prefs.appearance.isDark(systemDark))`). [dark] wins.
 * Read the effective flag below the theme with `DrokpoTheme.isDark`, never
 * `isSystemInDarkTheme()` (the in-app override would be ignored).
 *
 * Also keeps the status/navigation bar icon colours in step with the flag:
 * `enableEdgeToEdge()` only follows the *system* setting, so an in-app
 * Light/Dark override would otherwise leave white icons on a white bar.
 */
@Composable
fun DrokpoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dark: Boolean = darkTheme,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkDrokpoColors else LightDrokpoColors
    val colorScheme = remember(dark) { drokpoColorScheme(colors) }
    val typography = DefaultDrokpoTypography
    val materialTypography = remember { typography.toMaterialTypography() }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalDrokpoColors provides colors,
        LocalDrokpoTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = materialTypography,
            shapes = DrokpoShapes,
            content = content,
        )
    }
}

/** Accessors for the Drokpo tokens, mirroring `MaterialTheme.colorScheme`. */
object DrokpoTheme {
    /** iOS-derived colour tokens (accent, brandRed, labels, grouped backgrounds…). */
    val colors: DrokpoColors
        @Composable @ReadOnlyComposable
        get() = LocalDrokpoColors.current

    /** The effective dark flag (honours the in-app Appearance override). */
    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalDrokpoColors.current.isDark

    /** iOS text styles (largeTitle … caption2). */
    val typography: DrokpoTypography
        @Composable @ReadOnlyComposable
        get() = LocalDrokpoTypography.current
}

/**
 * The Activity behind a (possibly wrapped) context. Inside a `DrokpoSheet`/`ModalBottomSheet` or
 * a full-screen `Dialog`, `LocalContext.current` is a `ContextThemeWrapper` around the Activity,
 * so `context is Activity` is false there.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
