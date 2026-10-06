package app.drokpo.android.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Light/dark override the user can pick in Settings — port of the iOS
 * `AppearanceMode` (persisted in `@AppStorage("drokpo.appearance")`).
 */
enum class AppearanceMode(val raw: String, val label: String) {
    System("system", "System"),
    Light("light", "Light"),
    Dark("dark", "Dark"),
    ;

    /** iOS `colorScheme` (nil for System → follow the device). */
    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        System -> systemIsDark
        Light -> false
        Dark -> true
    }

    companion object {
        /** Unknown/null → System, like `@AppStorage` falling back to its default. */
        fun fromRaw(raw: String?): AppearanceMode = entries.firstOrNull { it.raw == raw } ?: System
    }
}

private val Context.drokpoDataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppPreferences.STORE_NAME,
    // A corrupt file must never brick launch (the splash waits on isLoaded).
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Every value the iOS app keeps in `@AppStorage`/`UserDefaults`, in one
 * DataStore (`drokpo_prefs`). The keys keep their iOS names:
 *
 * | key                           | iOS origin                                  |
 * |-------------------------------|---------------------------------------------|
 * | `drokpo.appearance`           | `@AppStorage` (DrokpoApp, Settings, CommunitySettings) |
 * | `drokpo.blockedUsers`         | `UserDefaults` in BlockStore (JSON array)   |
 * | `drokpo.notificationsPrompted`| Android-only: iOS's "the system prompt only shows once" |
 *
 * Feature code must not add keys here — request them from the spine.
 */
class AppPreferences internal constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    internal constructor(context: Context) : this(context.applicationContext.drokpoDataStore)

    private val _appearance = MutableStateFlow(AppearanceMode.System)
    private val _isLoaded = MutableStateFlow(false)

    /** `@AppStorage("drokpo.appearance")`. Starts at System until the first read lands. */
    val appearance: StateFlow<AppearanceMode> = _appearance.asStateFlow()

    /** True after the first DataStore read — MainActivity keeps the splash up until then (no theme flash). */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /** Every preference snapshot; IO failures read as "nothing saved" (UserDefaults never throws). */
    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    init {
        scope.launch {
            // One collector feeds both flows, appearance first, so whoever
            // waits on isLoaded (the splash) never sees the default theme.
            data.collect { prefs ->
                _appearance.value = AppearanceMode.fromRaw(prefs[KEY_APPEARANCE])
                _isLoaded.value = true
            }
        }
    }

    /** Fire-and-forget write; [appearance] updates immediately. */
    fun setAppearance(mode: AppearanceMode) {
        _appearance.value = mode
        scope.launch { updateAppearance(mode) }
    }

    /** Suspending twin of [setAppearance] that returns once the value is on disk. */
    suspend fun updateAppearance(mode: AppearanceMode) {
        _appearance.value = mode
        try {
            store.edit { it[KEY_APPEARANCE] = mode.raw }
        } catch (e: IOException) {
            // Same as a failed UserDefaults write: the in-memory value still applies.
        }
    }

    // region Spine-internal keys

    /** `drokpo.blockedUsers` — raw JSON array of [BlockedUser] (BlockStore decodes it). */
    internal val blockedUsersJson: Flow<String?> = data.map { it[KEY_BLOCKED_USERS] }.distinctUntilChanged()

    /**
     * Atomic read-modify-write of `drokpo.blockedUsers` (DataStore serialises
     * edits, so a record() racing a reset() can't lose either).
     */
    internal suspend fun updateBlockedUsersJson(transform: (String?) -> String) {
        try {
            store.edit { it[KEY_BLOCKED_USERS] = transform(it[KEY_BLOCKED_USERS]) }
        } catch (e: IOException) {
            // Best effort, like UserDefaults.
        }
    }

    /** `drokpo.notificationsPrompted` — PushService showed the POST_NOTIFICATIONS dialog once. */
    internal val notificationsPrompted: Flow<Boolean> =
        data.map { it[KEY_NOTIFICATIONS_PROMPTED] ?: false }.distinctUntilChanged()

    internal suspend fun notificationsPromptedNow(): Boolean = notificationsPrompted.first()

    internal suspend fun setNotificationsPrompted(value: Boolean) {
        try {
            store.edit { it[KEY_NOTIFICATIONS_PROMPTED] = value }
        } catch (e: IOException) {
            // Worst case the dialog is offered once more on a later launch.
        }
    }

    // endregion

    internal companion object {
        const val STORE_NAME = "drokpo_prefs"
        val KEY_APPEARANCE = stringPreferencesKey("drokpo.appearance")
        val KEY_BLOCKED_USERS = stringPreferencesKey("drokpo.blockedUsers")
        val KEY_NOTIFICATIONS_PROMPTED = booleanPreferencesKey("drokpo.notificationsPrompted")
    }
}
