package app.drokpo.android.core

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's singletons — the Android stand-in for iOS's `.shared` instances
 * and the `SessionStore` environment object (there is no DI framework, as on
 * iOS). Initialised once from `DrokpoApplication.onCreate`, after Firebase.
 */
object AppGraph {
    @Volatile
    private var application: Application? = null

    /** Called first thing in DrokpoApplication.onCreate() (after FirebaseApp init). Idempotent. */
    fun init(app: Application) {
        synchronized(this) {
            if (application != null) return
            application = app
        }
        // iOS creates SessionStore in App.init, so the auth listener starts
        // resolving while the launch screen is still up.
        session.start()
    }

    val app: Application
        get() = application ?: error("AppGraph.init(app) must run in DrokpoApplication.onCreate first")

    /**
     * App-lifetime scope: SupervisorJob() + Dispatchers.Main.immediate. For
     * work that must outlive a screen (signOut, analytics events, token
     * upload, fire-and-forget likes).
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs: AppPreferences by lazy { AppPreferences(app) }
    val session: SessionStore by lazy { SessionStore(appScope) }
    val blocks: BlockStore by lazy { BlockStore(prefs, appScope) }
    val deepLinks: DeepLinkRouter by lazy { DeepLinkRouter() }
    val push: PushService by lazy { PushService(app, prefs, appScope) }
}
