package app.drokpo.android.core

import app.drokpo.android.BuildConfig
import com.google.firebase.FirebaseApp
import java.net.URI

/** Port of the iOS `AppConfig` enum. */
object AppConfig {
    /**
     * Backend base URL — Firebase Hosting, which rewrites every /api/ path to the
     * drokpo-api Cloud Run service.
     */
    val API_BASE_URL: String = BuildConfig.API_BASE_URL

    /** Privacy policy hosted alongside the backend. */
    val PRIVACY_POLICY_URL: String = BuildConfig.PRIVACY_POLICY_URL

    /**
     * Host of the shareable `https://{host}/s/{type}/{id}` links (the same
     * Hosting site as the API — iOS compares against `apiBaseURL.host`).
     */
    val SHARE_HOST: String = hostOf(BuildConfig.API_BASE_URL) ?: "drokpo-backend.web.app"

    @Volatile
    private var firebaseReady = false

    /**
     * iOS: "is GoogleService-Info.plist bundled". Android: the build had a
     * google-services.json (BuildConfig.HAS_FIREBASE_CONFIG) **and** the
     * default FirebaseApp actually initialised — a json without usable values
     * must also land on the setup notice instead of crashing on the first
     * FirebaseAuth call. Only `true` is cached: a read that happens before
     * DrokpoApplication.onCreate finished initialising Firebase isn't final.
     */
    val hasFirebaseConfig: Boolean
        get() {
            if (!BuildConfig.HAS_FIREBASE_CONFIG) return false
            if (firebaseReady) return true
            val ready = try {
                FirebaseApp.getInstance()
                true
            } catch (e: IllegalStateException) {
                false
            }
            if (ready) firebaseReady = true
            return ready
        }

    /** "1.1 (37)" — iOS "\(CFBundleShortVersionString) (\(CFBundleVersion))". */
    val versionLabel: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private fun hostOf(url: String): String? =
        try {
            URI(url).host?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
}
