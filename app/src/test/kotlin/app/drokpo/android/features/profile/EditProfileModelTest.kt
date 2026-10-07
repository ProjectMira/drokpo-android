@file:OptIn(ExperimentalCoroutinesApi::class)

package app.drokpo.android.features.profile

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.ProfileUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class EditProfileModelTest {
    @get:Rule
    val main = ProfileMainDispatcherRule()

    private val profile = Profile(uid = "me", displayName = "Tenzin", dob = "1996-04-12", region = "Nepal")
    private val saved = mutableListOf<ProfileUpdate>()
    private var refreshes = 0
    private var saveFailure: Exception? = null
    private var saveGate: CompletableDeferred<Unit>? = null

    private fun TestScope.model(scope: CoroutineScope = this) = EditProfileModel(
        profile = profile,
        onSaved = { refreshes++ },
        saveProfile = { body ->
            saved += body
            saveGate?.await()
            saveFailure?.let { throw it }
        },
        saveScope = scope,
        today = LocalDate.of(2026, 10, 7),
    )

    @Test
    fun savePatchesRefreshesThenFinishes() = runTest(main.dispatcher) {
        val model = model()
        val gate = CompletableDeferred<Unit>()
        saveGate = gate
        model.updateForm(model.state.form.copy(displayName = " Tenzin Dolma "))
        model.save()
        assertTrue(model.state.isSaving)
        assertFalse(model.state.canSave)
        model.save() // ignored while saving
        advanceUntilIdle()
        assertEquals(1, saved.size)
        assertEquals("Tenzin Dolma", saved.single().displayName)
        assertEquals("1996-04-12", saved.single().dob)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, refreshes)
        assertTrue(model.state.finished)
        assertFalse(model.state.isSaving)
    }

    @Test
    fun failedSaveShowsCouldNotSaveAndStaysOpen() = runTest(main.dispatcher) {
        val model = model()
        saveFailure = ApiError.Http(422, "dob: must be at least 18")
        model.save()
        advanceUntilIdle()
        assertEquals("dob: must be at least 18", model.state.errorMessage)
        assertFalse(model.state.finished)
        assertFalse(model.state.isSaving)
        assertEquals(0, refreshes)

        model.dismissError()
        assertNull(model.state.errorMessage)
    }

    @Test
    fun blankNameCannotBeSaved() = runTest(main.dispatcher) {
        val model = model()
        model.updateForm(model.state.form.copy(displayName = "  "))
        model.save()
        advanceUntilIdle()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun locationFixIsSentWithTheNextSave() = runTest(main.dispatcher) {
        val model = model()
        val fix = GeoLocation(lat = 27.7, lng = 85.3)
        val pending = CompletableDeferred<GeoLocation?>()
        model.refreshLocation(request = { pending.await() }, isDenied = { false })
        advanceUntilIdle()
        assertTrue(model.state.isLocating)
        model.refreshLocation(request = { error("only one request at a time") }, isDenied = { false })

        pending.complete(fix)
        advanceUntilIdle()
        assertFalse(model.state.isLocating)
        assertEquals(EditProfileCopy.LOCATION_UPDATED, model.state.locationStatus)
        assertEquals(fix, model.state.updatedLocation)

        model.save()
        advanceUntilIdle()
        assertEquals(fix, saved.single().location)
    }

    @Test
    fun deniedLocationOffersSettingsAndKeepsSavedLocation() = runTest(main.dispatcher) {
        val model = model()
        model.refreshLocation(request = { null }, isDenied = { true })
        advanceUntilIdle()
        assertTrue(model.state.locationDenied)
        assertEquals(EditProfileCopy.LOCATION_DENIED, model.state.locationStatus)
        assertNull(model.state.updatedLocation)

        model.refreshLocation(request = { null }, isDenied = { false })
        advanceUntilIdle()
        assertEquals(EditProfileCopy.LOCATION_FAILED, model.state.locationStatus)
        assertTrue(model.state.locationDenied)

        model.save()
        advanceUntilIdle()
        assertNull(saved.single().location)
    }

    @Test
    fun editsApplyToTheCurrentForm() = runTest(main.dispatcher) {
        val model = model()
        model.updateForm(model.state.form.toggleLanguage("Tibetan"))
        model.updateForm(model.state.form.answering("teaChoice", "Chai"))
        assertEquals(setOf("Tibetan"), model.state.form.languages)
        assertEquals(mapOf("teaChoice" to "Chai"), model.state.form.answers)
        assertEquals("Nepal", model.state.form.region)
    }
}
