package app.drokpo.android.features.communities

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.model.CommunityAddress
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.ContactPerson
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Socials
import app.drokpo.android.features.communityhome.CommunityEditorFields
import app.drokpo.android.features.communityhome.CommunityEditorUiState
import app.drokpo.android.features.communityhome.CommunityProfileEditorModel
import app.drokpo.android.features.communityhome.SAVED_BADGE_MILLIS
import app.drokpo.android.features.communityhome.nonEmptyTrimmed
import app.drokpo.android.features.communityhome.trimWhitespaces
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityProfileEditorTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val photo0 = Photo(storagePath = "communities/c1/photos/0.jpg", order = 0)
    private val community = CommunityProfile(
        uid = "c1",
        name = "Tibetan Association",
        description = "Events and classes",
        website = "https://example.org",
        phone = "+1 416",
        email = "hi@example.org",
        contactPerson = ContactPerson(name = "Tashi", role = "Coordinator", phone = "+1 499", email = "t@example.org"),
        address = CommunityAddress(line1 = "40 Titan Rd", city = "Toronto", state = "ON", country = "Canada", postalCode = "M8Z"),
        socials = Socials(instagram = "tat", youtube = "TAT", tiktok = "tat.tok", facebook = "tatfb", x = "tat_x"),
        photos = listOf(photo0),
        verification = "verified",
        memberCount = 128,
    )

    // region Whitespace helpers

    @Test
    fun trimWhitespacesMatchesSwiftWhitespacesSet() {
        assertEquals("a b", "  a b \t".trimWhitespaces())
        // U+00A0 / U+3000 are Unicode Zs, like Swift's .whitespaces.
        assertEquals("x", " x　".trimWhitespaces())
        // Newlines are NOT in .whitespaces.
        assertEquals("\nx\n", " \nx\n ".trimWhitespaces())
        assertNull("  \t ".nonEmptyTrimmed())
        assertEquals("ok", " ok ".nonEmptyTrimmed())
    }

    // endregion

    // region Fields: load, dirty, blockers, body

    @Test
    fun fieldsLoadFromTheCommunityWithMissingValuesEmpty() {
        val fields = CommunityEditorFields.from(CommunityProfile(uid = "c2", name = "Only name"))
        assertEquals("Only name", fields.name)
        assertEquals(CommunityEditorFields(name = "Only name"), fields)

        val full = CommunityEditorFields.from(community)
        assertEquals("Tashi", full.contactName)
        assertEquals("M8Z", full.postalCode)
        assertEquals("tatfb", full.facebook)
    }

    @Test
    fun dirtyMeansDifferentFromTheCommunity() {
        val fields = CommunityEditorFields.from(community)
        assertFalse(fields.isDirty(community))
        assertTrue(fields.copy(city = "Ottawa").isDirty(community))
        // No community → never dirty (iOS `guard let community else { return false }`).
        assertFalse(fields.copy(city = "Ottawa").isDirty(null))
    }

    @Test
    fun saveBlockersMirrorTheBackendValidation() {
        val ok = CommunityEditorFields.from(community)
        assertNull(ok.saveBlocker)
        assertEquals("Name must be 2–80 characters.", ok.copy(name = " T ").saveBlocker)
        assertEquals("Name must be 2–80 characters.", ok.copy(name = "x".repeat(81)).saveBlocker)
        assertNull(ok.copy(name = "x".repeat(80)).saveBlocker)
        // Counted in code points (what the backend's len() sees): one emoji is one character.
        assertEquals("Name must be 2–80 characters.", ok.copy(name = "🏔").saveBlocker)
        assertNull(ok.copy(name = "🏔🏔").saveBlocker)
        assertEquals("Description can't be empty.", ok.copy(description = "   ").saveBlocker)
        assertEquals("Website must start with https://.", ok.copy(website = "http://example.org").saveBlocker)
        assertNull(ok.copy(website = "  ").saveBlocker)
        // Name is checked first.
        assertEquals("Name must be 2–80 characters.", ok.copy(name = "", description = "").saveBlocker)
    }

    @Test
    fun updateBodySendsEmptyStringsToClearAndOmitsNeverClearableFields() {
        val cleared = CommunityEditorFields(
            name = "  TAT  ",
            description = " Events ",
            website = " ",
            email = "  ",
            contactName = "",
            contactRole = "",
            city = "",
            country = " ",
            instagram = " tat ",
        )
        val body = cleared.toUpdate()
        assertEquals("TAT", body.name)
        assertEquals("Events", body.description)
        assertEquals("", body.website)
        assertEquals("", body.phone)
        assertNull(body.email)
        val contact = body.contactPerson!!
        assertNull(contact.name)
        assertEquals("", contact.role)
        assertEquals("", contact.phone)
        assertEquals("", contact.email)
        val address = body.address!!
        assertEquals("", address.line1)
        assertNull(address.city)
        assertEquals("", address.state)
        assertNull(address.country)
        assertEquals("", address.postalCode)
        val socials = body.socials!!
        assertEquals("tat", socials.instagram)
        assertEquals("", socials.facebook)
        // x / wechat aren't edited here: omitted, i.e. unchanged.
        assertNull(socials.x)
        assertNull(socials.wechat)

        val kept = CommunityEditorFields.from(community).toUpdate()
        assertEquals("hi@example.org", kept.email)
        assertEquals("Tashi", kept.contactPerson?.name)
        assertEquals("Toronto", kept.address?.city)
        assertEquals("Canada", kept.address?.country)
    }

    @Test
    fun uiStateCapsPhotosAtSix() {
        val five = community.copy(photos = (0 until 5).map { Photo("p$it") })
        assertTrue(CommunityEditorUiState(five, CommunityEditorFields()).canAddPhoto)
        val six = community.copy(photos = (0 until 6).map { Photo("p$it") })
        assertFalse(CommunityEditorUiState(six, CommunityEditorFields()).canAddPhoto)
        assertFalse(CommunityEditorUiState(community.copy(verification = "pending"), CommunityEditorFields()).isVerified)
    }

    // endregion

    // region Model: session sync

    private class Session(initial: CommunityProfile?) {
        val community = MutableStateFlow(initial)
        var refreshes = 0
        /** What the next refreshProfile() makes the session hold (null = unchanged). */
        var nextCommunity: CommunityProfile? = null

        val refresh: suspend () -> Unit = {
            refreshes++
            nextCommunity?.let { community.value = it }
        }
    }

    @Test
    fun startsFromTheSessionCommunity() = runTest {
        val session = Session(community)
        val model = CommunityProfileEditorModel(session.community, session.refresh, FakeCommunityAccountApi())
        assertEquals(CommunityEditorFields.from(community), model.uiState.fields)
        assertFalse(model.uiState.isDirty)
    }

    @Test
    fun sessionChangesReloadUntouchedFields() = runTest {
        val session = Session(community)
        val model = CommunityProfileEditorModel(session.community, session.refresh, FakeCommunityAccountApi())
        advanceUntilIdle()

        val remote = community.copy(name = "Renamed elsewhere", photos = listOf(photo0, Photo("p1")))
        session.community.value = remote
        advanceUntilIdle()

        assertEquals("Renamed elsewhere", model.uiState.fields.name)
        assertEquals(2, model.uiState.photos.size)
        assertFalse(model.uiState.isDirty)
    }

    @Test
    fun sessionChangesNeverWipeTypedEdits() = runTest {
        val session = Session(community)
        val model = CommunityProfileEditorModel(session.community, session.refresh, FakeCommunityAccountApi())
        advanceUntilIdle()

        model.updateFields(model.uiState.fields.copy(description = "Typed but unsaved"))
        // e.g. a photo upload refreshes the session.
        session.community.value = community.copy(photos = listOf(photo0, Photo("p1")))
        advanceUntilIdle()

        assertEquals("Typed but unsaved", model.uiState.fields.description)
        assertEquals(2, model.uiState.photos.size)
        assertTrue(model.uiState.isDirty)
    }

    @Test
    fun aCommunityArrivingLateFillsTheForm() = runTest {
        val session = Session(null)
        val model = CommunityProfileEditorModel(session.community, session.refresh, FakeCommunityAccountApi())
        advanceUntilIdle()
        assertEquals(CommunityEditorFields(), model.uiState.fields)

        session.community.value = community
        advanceUntilIdle()
        assertEquals("Tibetan Association", model.uiState.fields.name)
    }

    // endregion

    // region Model: save

    @Test
    fun savePatchesRefreshesReloadsAndShowsSavedForTwoSeconds() = runTest {
        val session = Session(community)
        val api = FakeCommunityAccountApi()
        val model = CommunityProfileEditorModel(session.community, session.refresh, api)
        advanceUntilIdle()

        model.updateFields(model.uiState.fields.copy(name = " TAT Toronto ", website = ""))
        session.nextCommunity = community.copy(name = "TAT Toronto", website = null)
        model.save()
        assertTrue(model.uiState.isSaving)
        runCurrent()

        assertEquals(listOf("patch"), api.calls)
        assertEquals("TAT Toronto", api.updates.single().name)
        assertEquals("", api.updates.single().website)
        assertEquals(1, session.refreshes)
        // Reloaded from the refreshed session: trimmed name, cleared website, clean.
        assertEquals("TAT Toronto", model.uiState.fields.name)
        assertEquals("", model.uiState.fields.website)
        assertFalse(model.uiState.isDirty)
        assertTrue(model.uiState.justSaved)

        advanceTimeBy(SAVED_BADGE_MILLIS - 1)
        assertTrue(model.uiState.justSaved)
        advanceTimeBy(2)
        assertFalse(model.uiState.justSaved)
        assertFalse(model.uiState.isSaving)
    }

    @Test
    fun saveIsBlockedByTheSaveBlocker() = runTest {
        val session = Session(community)
        val api = FakeCommunityAccountApi()
        val model = CommunityProfileEditorModel(session.community, session.refresh, api)
        model.updateFields(model.uiState.fields.copy(description = ""))
        assertEquals("Description can't be empty.", model.uiState.saveBlocker)

        model.save()
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
        assertFalse(model.uiState.isSaving)
    }

    @Test
    fun failedSaveAlertsAndKeepsTheEdits() = runTest {
        val session = Session(community)
        val api = FakeCommunityAccountApi().apply { failWith = ApiError.Http(422, "name must be 2-80 characters") }
        val model = CommunityProfileEditorModel(session.community, session.refresh, api)
        model.updateFields(model.uiState.fields.copy(phone = "+1 000"))

        model.save()
        advanceUntilIdle()

        assertEquals("name must be 2-80 characters", model.uiState.errorMessage)
        assertEquals("+1 000", model.uiState.fields.phone)
        assertFalse(model.uiState.isSaving)
        assertFalse(model.uiState.justSaved)
        assertEquals(0, session.refreshes)
        model.dismissError()
        assertNull(model.uiState.errorMessage)
    }

    // endregion

    // region Model: photos

    @Test
    fun addPhotoUploadsThenConfirmsAtTheNextOrderThenRefreshes() = runTest {
        val session = Session(community.copy(photos = listOf(photo0, Photo("p1"))))
        val api = FakeCommunityAccountApi()
        val model = CommunityProfileEditorModel(session.community, session.refresh, api)

        model.addPhoto { "communities/c1/photos/new.jpg" }
        assertTrue(model.uiState.isWorking)
        advanceUntilIdle()

        assertEquals(listOf("confirmPhoto(communities/c1/photos/new.jpg,2)"), api.calls)
        assertEquals(1, session.refreshes)
        assertFalse(model.uiState.isWorking)
    }

    @Test
    fun anUnreadableImageAlertsWithTheUploaderMessage() = runTest {
        val session = Session(community)
        val api = FakeCommunityAccountApi()
        val model = CommunityProfileEditorModel(session.community, session.refresh, api)

        model.addPhoto { throw app.drokpo.android.core.PhotoUploaderError.InvalidImage() }
        advanceUntilIdle()

        assertEquals("That photo couldn't be processed. Try a different one.", model.uiState.errorMessage)
        assertTrue(api.calls.isEmpty())
        assertFalse(model.uiState.isWorking)
    }

    @Test
    fun deletePhotoDeletesByStoragePathThenRefreshes() = runTest {
        val session = Session(community)
        val api = FakeCommunityAccountApi()
        val model = CommunityProfileEditorModel(session.community, session.refresh, api)

        model.deletePhoto(photo0)
        advanceUntilIdle()

        assertEquals(listOf("deletePhoto(communities/c1/photos/0.jpg)"), api.calls)
        assertEquals(1, session.refreshes)
    }

    @Test
    fun pullToRefreshRefreshesTheSession() = runTest {
        val session = Session(community)
        val model = CommunityProfileEditorModel(session.community, session.refresh, FakeCommunityAccountApi())
        model.refresh()
        assertTrue(model.uiState.isRefreshing)
        advanceUntilIdle()
        assertEquals(1, session.refreshes)
        assertFalse(model.uiState.isRefreshing)
    }

    // endregion
}
