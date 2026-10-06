package app.drokpo.android.features.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.drokpo.android.core.model.GeoLocation

/** Port of LocationFetcher (one-shot location). Obtain with rememberLocationFetcher(). (CONTRACT §B.2 — stub.) */
class LocationFetcher internal constructor() {
    /** Location permission denied with no further prompting possible (iOS .denied/.restricted) —
     *  only system Settings can change it. Valid after a requestLocation() call. */
    val isDenied: Boolean get() = false

    /** Requests ACCESS_COARSE_LOCATION + ACCESS_FINE_LOCATION if not granted (system dialog only — no
     *  custom "Allow" button, App Review 5.1.1(iv) rationale), then one current fix (Fused, balanced
     *  power, ~10 s timeout). Returns null on denial, failure or timeout. Never throws.
     *  Stub: always null (the onboarding flow then falls back to the region's coordinates). */
    suspend fun requestLocation(): GeoLocation? = null
}

/** Registers the permission launcher; must be called unconditionally in composition. */
@Composable
fun rememberLocationFetcher(): LocationFetcher = remember { LocationFetcher() }
