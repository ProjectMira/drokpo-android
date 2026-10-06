package app.drokpo.android.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.findActivity

/** Where a non-http(s) link is sent instead (same fallback as iOS SafariView). */
private const val SAFE_FALLBACK_URL = "https://drokpo-backend.web.app"

/**
 * In-app browser (Custom Tabs — the Android counterpart of iOS
 * `SFSafariViewController`/`SafariView`) for ad, news and community-post
 * links and other external pages, without leaving the app.
 *
 * SFSafariViewController crashes on anything but http(s), so iOS swaps such
 * URLs for the backend home page: the backend validates link fields, but a
 * Firestore doc edited outside those validators must not be able to send the
 * user somewhere unexpected. Same rule here.
 *
 * [darkTheme] themes the Custom Tab toolbar to match the app's appearance
 * override; null follows the system. Returns false if no browser could open
 * the link.
 */
fun openInAppBrowser(context: Context, url: String, darkTheme: Boolean? = null): Boolean =
    openInAppBrowser(context, url.trim().toUri(), darkTheme)

/** [openInAppBrowser] for an already-parsed [Uri]. */
fun openInAppBrowser(context: Context, uri: Uri, darkTheme: Boolean? = null): Boolean {
    val scheme = uri.scheme?.lowercase()
    val safeUri = if (scheme == "https" || scheme == "http") uri else SAFE_FALLBACK_URL.toUri()

    val light = CustomTabColorSchemeParams.Builder()
        .setToolbarColor(0xFFFFFFFF.toInt())
        .setNavigationBarColor(0xFFFFFFFF.toInt())
        .build()
    val dark = CustomTabColorSchemeParams.Builder()
        .setToolbarColor(0xFF000000.toInt())
        .setNavigationBarColor(0xFF000000.toInt())
        .build()
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setShareState(CustomTabsIntent.SHARE_STATE_ON)
        .setColorScheme(
            when (darkTheme) {
                true -> CustomTabsIntent.COLOR_SCHEME_DARK
                false -> CustomTabsIntent.COLOR_SCHEME_LIGHT
                null -> CustomTabsIntent.COLOR_SCHEME_SYSTEM
            },
        )
        .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, light)
        .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, dark)
        .build()
    // Unwrap first: from sheet or cover content the context is a ContextThemeWrapper, and a
    // NEW_TASK launch would put the Custom Tab in its own task (own Recents entry, off Drokpo's
    // back stack) instead of on top of the app.
    val host = context.findActivity()
    if (host == null) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    return try {
        intent.launchUrl(host ?: context, safeUri)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * `context.openInAppBrowser(url)` — the CONTRACT.md §A.12 spelling of
 * [openInAppBrowser] (system colour scheme for the Custom Tab toolbar).
 */
fun Context.openInAppBrowser(url: String) {
    openInAppBrowser(this, url, darkTheme = null)
}

/**
 * iOS `UIApplication.openSettingsURLString` → this app's system settings page
 * (permissions), e.g. the "Open Settings" fallback after a denied permission.
 */
fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    val host = findActivity()
    if (host == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        (host ?: this).startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No settings activity (some kiosk/TV builds): nothing sensible to do.
    }
}

/**
 * Composable convenience: `val openUrl = rememberInAppBrowser(); openUrl(link)`.
 * Picks up the current theme so the Custom Tab matches an in-app dark/light override.
 */
@Composable
fun rememberInAppBrowser(): (String) -> Unit {
    val context = LocalContext.current
    val isDark = DrokpoTheme.colors.isDark
    return remember(context, isDark) { { url: String -> openInAppBrowser(context, url, isDark) } }
}
