package app.drokpo.android.services

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.drokpo.android.core.AppPreferences
import app.drokpo.android.core.AppearanceMode
import app.drokpo.android.core.BlockStore
import app.drokpo.android.core.BlockedUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferencesAndBlocksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun newPrefs(name: String = "prefs"): AppPreferences {
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.root.resolve("$name.preferences_pb")
        }
        return AppPreferences(store, scope)
    }

    private suspend fun <T> StateFlow<T>.awaitValue(predicate: (T) -> Boolean): T =
        withTimeout(5_000) { first(predicate) }

    // region AppearanceMode

    @Test
    fun appearanceRawValuesMatchIosAppStorage() {
        assertEquals(listOf("system", "light", "dark"), AppearanceMode.entries.map { it.raw })
        assertEquals(listOf("System", "Light", "Dark"), AppearanceMode.entries.map { it.label })
        assertEquals(AppearanceMode.Dark, AppearanceMode.fromRaw("dark"))
        assertEquals(AppearanceMode.System, AppearanceMode.fromRaw(null))
        assertEquals(AppearanceMode.System, AppearanceMode.fromRaw("sepia"))
    }

    @Test
    fun appearanceResolvesAgainstTheSystem() {
        assertTrue(AppearanceMode.System.isDark(systemIsDark = true))
        assertFalse(AppearanceMode.System.isDark(systemIsDark = false))
        assertFalse(AppearanceMode.Light.isDark(systemIsDark = true))
        assertTrue(AppearanceMode.Dark.isDark(systemIsDark = false))
    }

    // endregion

    @Test
    fun defaultsAfterFirstRead() = runBlocking<Unit> {
        val prefs = newPrefs()
        prefs.isLoaded.awaitValue { it }
        assertEquals(AppearanceMode.System, prefs.appearance.value)
        assertFalse(prefs.notificationsPromptedNow())
    }

    @Test
    fun valuesSurviveARelaunch() = runBlocking<Unit> {
        // First "launch": its own scope, cancelled afterwards (DataStore allows
        // one active instance per file).
        val firstLaunch = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = tmp.root.resolve("relaunch.preferences_pb")
        val first = AppPreferences(PreferenceDataStoreFactory.create(scope = firstLaunch) { file }, firstLaunch)
        first.isLoaded.awaitValue { it }
        first.setAppearance(AppearanceMode.Dark)
        // Optimistic: the UI sees it immediately.
        assertEquals(AppearanceMode.Dark, first.appearance.value)
        first.updateAppearance(AppearanceMode.Dark)
        first.setNotificationsPrompted(true)
        firstLaunch.cancel()
        firstLaunch.coroutineContext[Job]?.join()

        val second = newPrefs("relaunch")
        second.isLoaded.awaitValue { it }
        assertEquals(AppearanceMode.Dark, second.appearance.value)
        assertTrue(second.notificationsPromptedNow())
    }

    @Test
    fun blockStoreRecordsNewestFirstWithoutDuplicates() = runBlocking<Unit> {
        var now = 1_000L
        val blocks = BlockStore(newPrefs(), scope, unblockRequest = {}, clock = { now++ })
        blocks.record("a", "Ana")
        blocks.blocked.awaitValue { it.size == 1 }
        blocks.record("b", null)
        blocks.blocked.awaitValue { it.size == 2 }
        blocks.record("a", "Ana again")
        // Give the duplicate write a chance to land, then check nothing changed.
        blocks.record("c", "Chime")
        val list = blocks.blocked.awaitValue { it.size == 3 }
        assertEquals(listOf("c", "b", "a"), list.map { it.uid })
        assertEquals("Ana", list.last().displayName)
    }

    @Test
    fun unblockForgetsOnlyOnSuccess() = runBlocking<Unit> {
        var fail = true
        val blocks = BlockStore(
            newPrefs(),
            scope,
            unblockRequest = { if (fail) throw IllegalStateException("offline") },
        )
        blocks.record("a", "Ana")
        val entry = blocks.blocked.awaitValue { it.isNotEmpty() }.single()
        try {
            blocks.unblock(entry)
            fail("unblock should rethrow")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(listOf("a"), blocks.blocked.value.map { it.uid })

        fail = false
        blocks.unblock(entry)
        blocks.blocked.awaitValue { it.isEmpty() }
    }

    @Test
    fun resetClearsEverything() = runBlocking<Unit> {
        val blocks = BlockStore(newPrefs(), scope, unblockRequest = {})
        blocks.record("a", "Ana")
        blocks.record("b", "Bo")
        blocks.blocked.awaitValue { it.size == 2 }
        blocks.reset()
        blocks.blocked.awaitValue { it.isEmpty() }
    }

    @Test
    fun blockedListJsonRoundTripsAndToleratesJunk() {
        val list = listOf(BlockedUser("a", "Ana", 2L), BlockedUser("b", null, 1L))
        assertEquals(list, BlockStore.decode(BlockStore.encode(list)))
        assertEquals(emptyList<BlockedUser>(), BlockStore.decode(null))
        assertEquals(emptyList<BlockedUser>(), BlockStore.decode("not json"))
        assertEquals(listOf(BlockedUser("x", null, 5L)), BlockStore.decode("""[{"uid":"x","blockedAt":5,"extra":1}]"""))
        val recorded = BlockStore.recording(list, BlockedUser("c", null, 3L))
        assertEquals(listOf("c", "a", "b"), recorded.map { it.uid })
        assertEquals(recorded, BlockStore.recording(recorded, BlockedUser("a", "dup", 9L)))
    }
}
