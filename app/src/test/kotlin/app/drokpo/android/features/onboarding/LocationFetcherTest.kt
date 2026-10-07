package app.drokpo.android.features.onboarding

import app.drokpo.android.core.model.GeoLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationFetcherTest {
    private val here = GeoLocation(lat = 32.22, lng = 76.32)

    private class FakePlatform(
        var granted: Boolean = false,
        /** What the system dialog answers. */
        var userAllows: Boolean = true,
        var canAskAgain: Boolean = true,
        var fix: GeoLocation? = null,
        var fixDelayMs: Long = 0,
        var fixError: Exception? = null,
        /** `drokpo.locationPermissionRequested`; null = use the interface default. */
        var requestedBefore: Boolean? = null,
    ) : LocationPlatform {
        var dialogs = 0
        var fixes = 0

        override suspend fun wasRequestedBefore(): Boolean = requestedBefore ?: true

        override suspend fun markRequested() {
            if (requestedBefore != null) requestedBefore = true
        }

        override fun hasPermission() = granted

        override suspend fun requestPermission(): Boolean {
            dialogs += 1
            if (userAllows) granted = true
            return granted
        }

        override fun canAskAgain() = canAskAgain

        override suspend fun currentLocation(): GeoLocation? {
            fixes += 1
            if (fixDelayMs > 0) delay(fixDelayMs)
            fixError?.let { throw it }
            return fix
        }
    }

    @Test
    fun alreadyGrantedSkipsTheDialog() = runTest {
        val platform = FakePlatform(granted = true, fix = here)
        val fetcher = LocationFetcher(platform)
        assertEquals(here, fetcher.requestLocation())
        assertEquals(0, platform.dialogs)
        assertFalse(fetcher.isDenied)
    }

    @Test
    fun notGrantedAsksThroughTheSystemDialogThenFetches() = runTest {
        val platform = FakePlatform(fix = here)
        val fetcher = LocationFetcher(platform)
        assertEquals(here, fetcher.requestLocation())
        assertEquals(1, platform.dialogs)
        assertEquals(1, platform.fixes)
    }

    @Test
    fun aFirstDenialCanStillBeAskedAgain() = runTest {
        val platform = FakePlatform(userAllows = false, canAskAgain = true, fix = here)
        val fetcher = LocationFetcher(platform)
        assertNull(fetcher.requestLocation())
        assertEquals(0, platform.fixes)
        // Android will still show the dialog next time — not iOS's `.denied` yet.
        assertFalse(fetcher.isDenied)
    }

    @Test
    fun aPermanentDenialReportsDeniedUntilGrantedInSettings() = runTest {
        val platform = FakePlatform(userAllows = false, canAskAgain = false, fix = here)
        val fetcher = LocationFetcher(platform)
        assertFalse(fetcher.isDenied) // only valid after a request
        assertNull(fetcher.requestLocation())
        assertTrue(fetcher.isDenied)

        // Turned on from system Settings: the live check clears it, like iOS's authorizationStatus.
        platform.granted = true
        assertFalse(fetcher.isDenied)
        assertEquals(here, fetcher.requestLocation())
        platform.granted = false
        assertFalse(fetcher.isDenied) // the successful request reset the flag
    }

    @Test
    fun aFirstEverDialogDismissedWithoutAnAnswerIsNotDeniedForGood() = runTest {
        // Android 11+: back / tap outside on the first dialog leaves rationale false, like a
        // permanent denial. Only once the dialog has been shown before does that mean `.denied`.
        val platform = FakePlatform(userAllows = false, canAskAgain = false, requestedBefore = false)
        val fetcher = LocationFetcher(platform)
        assertNull(fetcher.requestLocation())
        assertFalse(fetcher.isDenied)
        assertEquals(true, platform.requestedBefore)

        assertNull(fetcher.requestLocation())
        assertTrue(fetcher.isDenied)
        assertEquals(2, platform.dialogs)
    }

    @Test
    fun aSlowFixTimesOutAfterTenSeconds() = runTest {
        val platform = FakePlatform(granted = true, fix = here, fixDelayMs = 60_000)
        val fetcher = LocationFetcher(platform)
        val start = currentTime
        assertNull(fetcher.requestLocation())
        assertEquals(LocationFetcher.DEFAULT_TIMEOUT_MS, currentTime - start)
    }

    @Test
    fun providerErrorsBecomeNull() = runTest {
        val fetcher = LocationFetcher(FakePlatform(granted = true, fixError = SecurityException("revoked")))
        assertNull(fetcher.requestLocation())
        val noFix = LocationFetcher(FakePlatform(granted = true, fix = null))
        assertNull(noFix.requestLocation())
    }

    @Test
    fun cancellationStillPropagates() = runTest {
        val fetcher = LocationFetcher(FakePlatform(granted = true, fix = here, fixDelayMs = 5_000))
        val request = async { fetcher.requestLocation() }
        advanceTimeBy(1_000)
        runCurrent()
        request.cancel()
        val outcome = runCatching { request.await() }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
    }
}
