package app.drokpo.android.features.communities

import app.drokpo.android.core.ApiError
import app.drokpo.android.features.communityhome.CommunitySettingsModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunitySettingsModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun deleteRemovesTheCommunityThenSignsOut() = runTest {
        val api = FakeCommunityAccountApi()
        val events = mutableListOf<String>()
        val model = CommunitySettingsModel(api = api, signOut = { events += "signOut" })

        model.deleteCommunity()
        assertTrue(model.state.value.isDeleting)
        // A second tap while deleting is ignored.
        model.deleteCommunity()
        advanceUntilIdle()

        assertEquals(listOf("deleteCommunity"), api.calls)
        assertEquals(listOf("signOut"), events)
        assertFalse(model.state.value.isDeleting)
    }

    @Test
    fun failedDeleteAlertsAndStaysSignedIn() = runTest {
        val api = FakeCommunityAccountApi().apply { failWith = ApiError.Http(500, "Server error (500).") }
        var signedOut = false
        val model = CommunitySettingsModel(api = api, signOut = { signedOut = true })

        model.deleteCommunity()
        advanceUntilIdle()

        assertEquals("Server error (500).", model.state.value.errorMessage)
        assertFalse(signedOut)
        assertFalse(model.state.value.isDeleting)
        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }
}
