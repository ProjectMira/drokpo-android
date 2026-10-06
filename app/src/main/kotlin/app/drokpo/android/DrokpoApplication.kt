package app.drokpo.android

import android.app.Application
import android.util.Log
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.PushNotifications
import app.drokpo.android.core.newDrokpoImageLoader
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.google.firebase.FirebaseApp

/**
 * App entry point — the iOS `DrokpoApp.init` + `AppDelegate`
 * `didFinishLaunching`: Firebase, the app singletons, notification channels,
 * and Coil's app-wide image loader.
 */
class DrokpoApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // google-services.json is optional (gitignored; CI restores it). Without
        // it the app runs and shows the "Firebase not configured" notice
        // instead of crashing — iOS's `if AppConfig.hasFirebaseConfig`.
        if (BuildConfig.HAS_FIREBASE_CONFIG && FirebaseApp.getApps(this).isEmpty()) {
            // FirebaseInitProvider normally did this already; initializeApp
            // returns null when the generated values are missing.
            try {
                FirebaseApp.initializeApp(this)
            } catch (e: Exception) {
                Log.w(TAG, "Firebase initialisation failed", e)
            }
        }
        AppGraph.init(this)
        PushNotifications.createChannels(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = newDrokpoImageLoader(context)

    private companion object {
        const val TAG = "DrokpoApplication"
    }
}
