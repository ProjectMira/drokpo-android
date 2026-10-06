package app.drokpo.android.core

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.drokpo.android.core.model.FcmTokenIn
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.lang.ref.WeakReference

/**
 * Implemented by the Activity that owns the POST_NOTIFICATIONS
 * `ActivityResultLauncher` (MainActivity). The launcher has to be registered
 * by the Activity before it starts, so PushService asks the host to launch
 * it, and the host reports the answer back through
 * [PushService.onNotificationPermissionResult].
 */
interface NotificationPermissionHost {
    fun launchNotificationPermissionRequest()
}

/**
 * Keeps this device's FCM token registered with the backend
 * (POST/DELETE /api/profile/me/fcm-tokens) so the Cloud Functions can push
 * "new match", "someone likes you" and "new message" notifications.
 * Port of the iOS `PushService`.
 *
 * Android differences:
 * - The notification permission is a runtime permission only on API 33+;
 *   older versions have it granted at install (the user can still switch
 *   notifications off in system settings — treated like iOS "denied").
 * - iOS's system prompt can only ever show once; the Android dialog could be
 *   shown twice, so `drokpo.notificationsPrompted` keeps it to once.
 * - FCM mints a token at install regardless of permission; like iOS (whose
 *   token only exists after `registerForRemoteNotifications`), it is only
 *   uploaded while notifications are allowed.
 *
 * State is touched on the main thread only (the app scope).
 */
class PushService internal constructor(
    private val app: Application,
    private val prefs: AppPreferences = AppGraph.prefs,
    private val scope: CoroutineScope = AppGraph.appScope,
) {
    /** Latest token minted by FCM; may rotate at any time. */
    private var currentToken: String? = null

    /** Token the backend currently has for this device. */
    private var uploadedToken: String? = null
    private var uploadInFlight: String? = null

    private var host: WeakReference<NotificationPermissionHost>? = null

    /** A prompt was due but no started host Activity was around to show it. */
    private var promptDeferred = false
    private val promptMutex = Mutex()

    /**
     * Ask for notification permission (once) and sync the token. Called every
     * time the session becomes active (SessionStore) and once by MainTabs with
     * its activity. On API 33+, when POST_NOTIFICATIONS isn't granted and
     * hasn't been asked yet, shows the system dialog through the host
     * Activity ([activity] if it is a [NotificationPermissionHost], else the
     * attached one; a plain Activity falls back to
     * `ActivityCompat.requestPermissions`). If no Activity is in the
     * foreground, the prompt waits for MainActivity's next resume.
     *
     * When notifications are allowed it fetches the FCM token and uploads it
     * if the backend doesn't have it yet. Never throws; a failed upload is
     * retried on the next enable() or token rotation.
     */
    fun enable(activity: Activity? = null) {
        if (!AppConfig.hasFirebaseConfig) return
        scope.launch {
            if (needsRuntimePermission()) {
                promptForPermissionOnce(activity)
                return@launch
            }
            if (!notificationsAllowed()) return@launch
            syncToken()
        }
    }

    /**
     * Detach this device from the profile; call before signing out, while the
     * auth session is still valid. Errors are ignored.
     *
     * iOS can rely on `uploadedToken ?? currentToken` because FCM hands it the
     * token on every launch. Android's `onNewToken` fires only when a token is
     * minted or rotates, and [enable] fetches it only while notifications are
     * allowed — so in a session with notifications switched off both fields
     * are null even though the backend still has this device's token from an
     * earlier session. Fetch it then (no permission needed): otherwise the
     * token stays on this account and the next account to sign in here would
     * receive both accounts' pushes.
     */
    suspend fun unregister() {
        val token = uploadedToken ?: currentToken ?: fetchToken() ?: return
        // Cleared before the DELETE, not after: SessionStore bounds this call
        // with withTimeoutOrNull, and a cancelled DELETE must not leave the
        // token marked as uploaded — FCM keeps the same token across sign-out,
        // so the next sign-in's uploadTokenIfNeeded() would skip the POST.
        uploadedToken = null
        tryOrNull {
            ApiClient.delete<EmptyResponse>("/api/profile/me/fcm-tokens", listOf("token" to token))
        }
    }

    // region Activity host (MainActivity)

    /** MainActivity.onCreate — it owns the permission launcher. */
    fun attachPermissionHost(host: NotificationPermissionHost) {
        this.host = WeakReference(host)
    }

    fun detachPermissionHost(host: NotificationPermissionHost) {
        if (this.host?.get() === host) this.host = null
    }

    /** MainActivity.onResume — shows a prompt that was due while nothing was on screen. */
    fun onPermissionHostResumed(host: NotificationPermissionHost) {
        attachPermissionHost(host)
        if (!promptDeferred || !AppConfig.hasFirebaseConfig) return
        scope.launch {
            if (needsRuntimePermission()) promptForPermissionOnce(host as? Activity)
        }
    }

    /** The host's launcher callback. Granted → register right away (iOS: `guard granted`). */
    fun onNotificationPermissionResult(granted: Boolean) {
        if (!granted || !AppConfig.hasFirebaseConfig) return
        scope.launch { syncToken() }
    }

    // endregion

    // region Token

    /** DrokpoMessagingService.onNewToken (any thread). */
    internal fun onNewToken(token: String) {
        scope.launch {
            currentToken = token
            if (notificationsAllowed()) uploadTokenIfNeeded()
        }
    }

    private suspend fun syncToken() {
        fetchToken() ?: return
        uploadTokenIfNeeded()
    }

    /** This device's FCM token (also stored as [currentToken]), or null if FCM can't provide one. */
    private suspend fun fetchToken(): String? {
        val token = try {
            // Legacy registration token on purpose — see DrokpoMessagingService.onNewToken.
            @Suppress("DEPRECATION")
            FirebaseMessaging.getInstance().token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Also IllegalStateException when Firebase isn't initialised.
            Log.w(TAG, "FCM token fetch failed", e)
            return null
        }
        currentToken = token
        return token
    }

    private suspend fun uploadTokenIfNeeded() {
        val token = currentToken ?: return
        if (token == uploadedToken || token == uploadInFlight) return
        if (!isSignedIn()) return
        uploadInFlight = token
        try {
            ApiClient.post<EmptyResponse>("/api/profile/me/fcm-tokens", FcmTokenIn(token = token))
            uploadedToken = token
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Retried on the next enable() or token rotation.
        } finally {
            if (uploadInFlight == token) uploadInFlight = null
        }
    }

    // endregion

    // region Permission

    /** API 33+ and POST_NOTIFICATIONS not granted yet. */
    fun needsRuntimePermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /** The user hasn't blocked notifications for the app (iOS: authorization granted). */
    fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(app).areNotificationsEnabled()

    private suspend fun promptForPermissionOnce(activity: Activity?) = promptMutex.withLock {
        if (!needsRuntimePermission() || prefs.notificationsPromptedNow()) {
            promptDeferred = false
            return@withLock
        }
        val target = (activity as? NotificationPermissionHost) ?: host?.get()
        if (target == null && activity == null) {
            promptDeferred = true
            return@withLock
        }
        // Never fire the dialog at an Activity that isn't on screen (e.g. the
        // session finished loading while the app was in the background).
        val owner = (target ?: activity) as? LifecycleOwner
        if (owner != null && !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            promptDeferred = true
            return@withLock
        }
        promptDeferred = false
        prefs.setNotificationsPrompted(true)
        try {
            if (target != null) {
                target.launchNotificationPermissionRequest()
            } else if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATIONS,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show the notification permission dialog", e)
        }
    }

    // endregion

    private fun isSignedIn(): Boolean =
        try {
            FirebaseAuth.getInstance().currentUser != null
        } catch (e: IllegalStateException) {
            false
        }

    internal companion object {
        private const val TAG = "PushService"

        /** For hosts that use the plain `requestPermissions` fallback. */
        const val REQUEST_CODE_NOTIFICATIONS = 4_601
    }
}
