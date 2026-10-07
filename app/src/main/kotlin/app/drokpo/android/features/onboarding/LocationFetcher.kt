package app.drokpo.android.features.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.model.GeoLocation
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Port of the iOS `LocationFetcher` (one-shot location), CONTRACT.md §B.2.
 * Obtain one with [rememberLocationFetcher].
 *
 * iOS asks CoreLocation for when-in-use authorization only when the status is
 * `.notDetermined`, then takes a single `requestLocation()` fix. Android has
 * no "not determined" status, so [requestLocation] simply asks whenever the
 * permission isn't granted — the system itself stops showing the dialog after
 * the user has denied it twice (or picked "Don't ask again"), and then the
 * request comes back denied immediately, which is iOS's `.denied` path.
 *
 * There is deliberately no custom "Allow location" button anywhere: the
 * Allow/Don't Allow choice must live in the system dialog (App Review
 * guideline 5.1.1(iv) rejected the iOS pre-prompt button), so callers trigger
 * this straight from their own primary action (onboarding's Continue,
 * EditProfile's "Update my location").
 */
class LocationFetcher internal constructor(
    private val platform: LocationPlatform,
    /** How long one fix may take before we give up (iOS `requestLocation()` times out on its own). */
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
    /**
     * Set when the last request was denied and the system won't show the
     * dialog again. Snapshot state, so composables reading [isDenied] update.
     */
    private var deniedForGood by mutableStateOf(false)

    /**
     * Location permission denied with no further prompting possible (iOS
     * `.denied`/`.restricted`) — only system Settings can change it. Valid
     * after a [requestLocation] call; turns false again as soon as the
     * permission is granted (e.g. from Settings), like iOS's live status.
     */
    val isDenied: Boolean
        get() = deniedForGood && !safeHasPermission()

    /**
     * Requests ACCESS_COARSE_LOCATION + ACCESS_FINE_LOCATION if not granted
     * (system dialog only), then one current fix (Fused, balanced power,
     * ~10 s timeout). Returns null on denial, failure or timeout. Never
     * throws (coroutine cancellation still propagates).
     */
    suspend fun requestLocation(): GeoLocation? {
        try {
            if (!platform.hasPermission()) {
                val askedBefore = platform.wasRequestedBefore()
                platform.markRequested()
                val granted = platform.requestPermission()
                if (!granted) {
                    // Denied: remember whether Android will still ask next
                    // time. Only a request the system no longer shows counts
                    // as "denied for good" (EditProfile then offers Settings).
                    // A first-ever dialog dismissed with back or a tap outside
                    // (Android 11+) also reads "can't ask again", so it only
                    // counts once the dialog has been shown before.
                    deniedForGood = askedBefore && !platform.canAskAgain()
                    return null
                }
            }
            deniedForGood = false
            return withTimeoutOrNull(timeoutMillis) { platform.currentLocation() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // SecurityException (permission revoked mid-request), missing
            // Play services, location provider errors — all "no fix", like
            // iOS's didFailWithError → nil.
            return null
        }
    }

    private fun safeHasPermission(): Boolean =
        try {
            platform.hasPermission()
        } catch (e: Exception) {
            false
        }

    internal companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

/**
 * The platform half of [LocationFetcher] — permission state, the system
 * permission dialog and the fused location provider — split out so the
 * fetcher's decisions are unit-testable on the JVM with a fake.
 */
internal interface LocationPlatform {
    /** Coarse or fine location is granted (Android 12+ lets people grant only approximate). */
    fun hasPermission(): Boolean

    /** Shows the system dialog; true when either location permission ends up granted. */
    suspend fun requestPermission(): Boolean

    /**
     * After a denial: will the system show the dialog again
     * (`shouldShowRequestPermissionRationale`)? False ≙ iOS `.denied`.
     */
    fun canAskAgain(): Boolean

    /** One current fix, or null when none is available. Requires the permission. */
    suspend fun currentLocation(): GeoLocation?

    /**
     * Whether this install has shown the location dialog before
     * (`drokpo.locationPermissionRequested`). The default keeps platforms
     * without storage on the plain `canAskAgain` rule.
     */
    suspend fun wasRequestedBefore(): Boolean = true

    /** Records that the dialog is about to be shown. */
    suspend fun markRequested() {}
}

/** Registers the permission launcher; must be called unconditionally in composition. */
@Composable
fun rememberLocationFetcher(): LocationFetcher {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val platform = remember { AndroidLocationPlatform(context.applicationContext) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        platform.onPermissionResult(result)
    }
    DisposableEffect(platform, launcher, activity) {
        platform.attach(launcher, activity)
        onDispose { platform.detach(launcher) }
    }
    return remember(platform) { LocationFetcher(platform) }
}

/** Fused-location + runtime-permission implementation. Main thread only. */
private class AndroidLocationPlatform(private val context: Context) : LocationPlatform {
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var activity: Activity? = null

    /** The in-flight permission request; a second caller waits on the same dialog. */
    private var pending: CompletableDeferred<Boolean>? = null

    fun attach(launcher: ActivityResultLauncher<Array<String>>, activity: Activity?) {
        this.launcher = launcher
        this.activity = activity
    }

    fun detach(launcher: ActivityResultLauncher<Array<String>>) {
        if (this.launcher !== launcher) return
        this.launcher = null
        this.activity = null
        // The screen went away mid-dialog: its result can no longer arrive
        // here, so release whoever is waiting instead of suspending forever.
        pending?.complete(hasPermission())
        pending = null
    }

    fun onPermissionResult(result: Map<String, Boolean>) {
        val granted = result.values.any { it } || hasPermission()
        pending?.complete(granted)
        pending = null
    }

    override fun hasPermission(): Boolean =
        PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    override suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main.immediate) {
        pending?.let { return@withContext it.await() }
        val launcher = launcher ?: return@withContext false
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        try {
            launcher.launch(PERMISSIONS)
        } catch (e: IllegalStateException) {
            // Launcher not registered (host destroyed) — nothing to ask with.
            pending = null
            return@withContext false
        }
        try {
            deferred.await()
        } finally {
            if (pending === deferred) pending = null
        }
    }

    override suspend fun wasRequestedBefore(): Boolean =
        try {
            AppGraph.prefs.locationPermissionRequestedNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }

    override suspend fun markRequested() {
        try {
            AppGraph.prefs.setLocationPermissionRequested(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort, like UserDefaults.
        }
    }

    override fun canAskAgain(): Boolean {
        // Without an activity we can't tell; don't claim "denied for good".
        val activity = activity ?: return true
        return PERMISSIONS.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }

    // Only called after hasPermission() returned true (LocationFetcher), and a
    // SecurityException from a revoke in between is caught there.
    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): GeoLocation? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cancellation = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            // Leaves LocationFetcher's 10 s budget room for the cached fallback below.
            .setDurationMillis(PROVIDER_DURATION_MS)
            .build()
        val fresh = try {
            client.getCurrentLocation(request, cancellation.token).await()
        } finally {
            // Stops the provider request when the caller is cancelled (or
            // LocationFetcher's timeout fires); a no-op once it has finished.
            cancellation.cancel()
        }
        // No fresh fix (e.g. location switched off device-wide): a recent
        // cached one is still far better than the region centre.
        val location = fresh ?: client.lastLocation.await()
        return location?.let { GeoLocation(lat = it.latitude, lng = it.longitude) }
    }

    companion object {
        const val PROVIDER_DURATION_MS = 8_000L

        val PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
