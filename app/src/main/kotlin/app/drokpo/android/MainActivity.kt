package app.drokpo.android

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.NotificationPermissionHost
import app.drokpo.android.core.PushPayload
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * The single activity: splash, edge-to-edge, the app-wide appearance
 * override, incoming share links and push-notification taps (the iOS
 * `WindowGroup` + `onOpenURL` + the notification-tap delegate).
 *
 * singleTask (manifest), so links and taps that arrive while it's running
 * come through [onNewIntent].
 */
class MainActivity : ComponentActivity(), NotificationPermissionHost {

    // Registered before STARTED, as the Activity Result API requires;
    // PushService decides *when* to ask (iOS asks as the session becomes active).
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppGraph.push.onNotificationPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Hold the splash until the appearance preference is read, so a
        // forced Light/Dark choice never flashes the other theme first.
        splash.setKeepOnScreenCondition { !AppGraph.prefs.isLoaded.value }
        AppGraph.push.attachPermissionHost(this)

        // A recreated activity (process death) would replay the original
        // launch intent; it was already handled the first time.
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val appearance by AppGraph.prefs.appearance.collectAsStateWithLifecycle()
            val dark = appearance.isDark(isSystemInDarkTheme())

            // System bar icons follow the *effective* theme (the in-app
            // override included), not just the device setting.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }

            DrokpoTheme(darkTheme = dark) {
                RootScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppGraph.push.onPermissionHostResumed(this)
    }

    override fun onDestroy() {
        AppGraph.push.detachPermissionHost(this)
        super.onDestroy()
    }

    override fun launchNotificationPermissionRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * iOS `onOpenURL` (share links take precedence and return early) and the
     * push-tap handler: `drokpo://s/{type}/{id}` / `https://…/s/{type}/{id}`
     * → DeepLinkRouter.pendingShare; otherwise `type`/`matchId` extras (a
     * notification tap, cold start or not) → DeepLinkRouter.handle.
     * The router holds them until MainTabs exists, so this works signed out.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // Reopened from Recents: Android replays the original intent — a
        // stale link/tap the user already followed.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val handled = AppGraph.deepLinks.handleLaunch(
            dataString = intent.dataString,
            type = intent.getStringExtra(PushPayload.KEY_TYPE),
            matchId = intent.getStringExtra(PushPayload.KEY_MATCH_ID),
        )
        if (handled) {
            // Consumed — don't route the same link/tap again.
            intent.data = null
            intent.removeExtra(PushPayload.KEY_TYPE)
            intent.removeExtra(PushPayload.KEY_MATCH_ID)
        }
    }

    private companion object {
        // androidx.activity's default enableEdgeToEdge scrims (3-button navigation only).
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
