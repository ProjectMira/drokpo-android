package app.drokpo.android.features.communityonboarding

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.DrokpoJson
import app.drokpo.android.core.PhotoUploaderError
import app.drokpo.android.core.model.CommunityAddress
import app.drokpo.android.core.model.CommunityOnboardingIn
import app.drokpo.android.core.model.CommunityPhotoConfirm
import app.drokpo.android.core.model.CommunityUpdate
import app.drokpo.android.core.model.ContactPerson
import app.drokpo.android.core.model.Socials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityOnboardingModelTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeService : CommunityOnboardingService {
        /** Every POST /api/communities/onboarding, including the ones that failed. */
        var createAttempts = 0
        val created = mutableListOf<CommunityOnboardingIn>()
        val updated = mutableListOf<CommunityUpdate>()
        val uploaded = mutableListOf<String>()
        val confirmed = mutableListOf<CommunityPhotoConfirm>()
        val deleted = mutableListOf<String>()

        /** Successful photo calls in order ("upload:<uri>", "delete:<path>"). */
        val photoCalls = mutableListOf<String>()
        var createError: Exception? = null
        var updateError: Exception? = null
        var deleteError: Exception? = null
        var createGate: CompletableDeferred<Unit>? = null

        /** While set, every pick stays "loading" until it completes. */
        var loadGate: CompletableDeferred<Unit>? = null
        val failingUploads = mutableSetOf<String>()
        val unloadable = mutableSetOf<String>()

        override suspend fun createCommunity(body: CommunityOnboardingIn) {
            createAttempts += 1
            createGate?.await()
            createError?.let { throw it }
            created += body
        }

        override suspend fun updateCommunity(body: CommunityUpdate) {
            updateError?.let { throw it }
            updated += body
        }

        override suspend fun uploadPhoto(uri: String): String {
            if (uri in failingUploads) throw PhotoUploaderError.InvalidImage()
            uploaded += uri
            photoCalls += "upload:$uri"
            return "communities/c1/photos/${uploaded.size}-$uri.jpg"
        }

        override suspend fun confirmPhoto(body: CommunityPhotoConfirm) {
            confirmed += body
        }

        override suspend fun deletePhoto(storagePath: String) {
            deleteError?.let { throw it }
            deleted += storagePath
            photoCalls += "delete:$storagePath"
        }

        override suspend fun canLoadPhoto(uri: String): Boolean {
            loadGate?.await()
            return uri !in unloadable
        }
    }

    private val service = FakeService()
    private var refreshes = 0
    private var refreshGate: CompletableDeferred<Unit>? = null

    private fun model() = CommunityOnboardingModel(
        service = service,
        refreshSession = {
            refreshes += 1
            refreshGate?.await()
        },
    )

    private val required = CommunityOnboardingForm(
        name = "  Tibetan Association  ",
        communityDescription = "\nLosar, language classes.\n",
        email = " hello@tat.example.org ",
        contactName = " Tashi ",
        city = " Toronto",
        country = "Canada ",
    )

    /** Fills every step and walks to Address (nothing submitted yet). */
    private fun CommunityOnboardingModel.walkToAddress(form: CommunityOnboardingForm = required) {
        updateForm { form }
        repeat(3) { advance() }
        assertEquals(CommunityOnboardingStep.Address, uiState.step)
    }

    private fun TestScope.createCommunity(model: CommunityOnboardingModel) {
        model.walkToAddress()
        model.advance()
        advanceUntilIdle()
        assertEquals(CommunityOnboardingStep.Photos, model.uiState.step)
    }

    // region Validation (canAdvance)

    @Test
    fun basicsNeedsNameAndDescriptionAfterTrimming() {
        val step = CommunityOnboardingStep.Basics
        assertFalse(CommunityOnboardingForm().canAdvance(step))
        assertFalse(CommunityOnboardingForm(name = "TAT", communityDescription = "  \n ").canAdvance(step))
        assertFalse(CommunityOnboardingForm(name = "   ", communityDescription = "About").canAdvance(step))
        assertTrue(CommunityOnboardingForm(name = "TAT", communityDescription = "About").canAdvance(step))
    }

    @Test
    fun contactNeedsAnEmailWithAnAt() {
        val step = CommunityOnboardingStep.Contact
        assertFalse(CommunityOnboardingForm(website = "https://x.org", phone = "123").canAdvance(step))
        assertFalse(CommunityOnboardingForm(email = "hello.example.org").canAdvance(step))
        assertTrue(CommunityOnboardingForm(email = " @ ").canAdvance(step))
        assertTrue(CommunityOnboardingForm(email = "hello@example.org").canAdvance(step))
    }

    @Test
    fun contactPersonNeedsAName() {
        val step = CommunityOnboardingStep.ContactPerson
        assertFalse(CommunityOnboardingForm(contactRole = "Coordinator", contactEmail = "a@b").canAdvance(step))
        assertFalse(CommunityOnboardingForm(contactName = "  ").canAdvance(step))
        assertTrue(CommunityOnboardingForm(contactName = "Tashi").canAdvance(step))
    }

    @Test
    fun addressNeedsCityAndCountry() {
        val step = CommunityOnboardingStep.Address
        assertFalse(CommunityOnboardingForm(line1 = "40 Titan Rd", city = "Toronto").canAdvance(step))
        assertFalse(CommunityOnboardingForm(country = "Canada").canAdvance(step))
        assertFalse(CommunityOnboardingForm(city = " ", country = "Canada").canAdvance(step))
        assertTrue(CommunityOnboardingForm(city = "Toronto", country = "Canada").canAdvance(step))
    }

    @Test
    fun photosNeverBlockFinishing() {
        assertTrue(CommunityOnboardingForm().canAdvance(CommunityOnboardingStep.Photos))
    }

    @Test
    fun progressCountsFiveSteps() {
        assertEquals(0.2f, CommunityOnboardingUiState().progress, 0.0001f)
        assertEquals(1f, CommunityOnboardingUiState(step = CommunityOnboardingStep.Photos).progress, 0.0001f)
        assertEquals(6, CommunityOnboardingUiState().remainingPhotoSlots)
        val full = CommunityOnboardingUiState(photos = (0L until 6L).map { PickedPhoto(it, "p$it") })
        assertEquals(0, full.remainingPhotoSlots)
    }

    // endregion

    // region Request bodies

    @Test
    fun onboardingBodyTrimsRequiredAndDropsEmptyOptionals() {
        val body = required.copy(website = "  ", contactRole = "Coordinator ", instagram = " tat ").toOnboardingIn()
        assertEquals(
            CommunityOnboardingIn(
                name = "Tibetan Association",
                description = "Losar, language classes.",
                website = null,
                phone = null,
                email = "hello@tat.example.org",
                contactPerson = ContactPerson(name = "Tashi", role = "Coordinator"),
                address = CommunityAddress(city = "Toronto", country = "Canada"),
                socials = Socials(instagram = "tat"),
            ),
            body,
        )
        // Swift encodes nil optionals by omitting the key; an all-nil Socials is still `{}`.
        assertEquals(
            """{"name":"Tibetan Association","description":"Losar, language classes.",""" +
                """"email":"hello@tat.example.org","contactPerson":{"name":"Tashi","role":"Coordinator"},""" +
                """"address":{"city":"Toronto","country":"Canada"},"socials":{"instagram":"tat"}}""",
            DrokpoJson.encodeToString(CommunityOnboardingIn.serializer(), body),
        )
        assertEquals(
            "{}",
            DrokpoJson.encodeToString(Socials.serializer(), required.toOnboardingIn().socials!!),
        )
    }

    @Test
    fun updateBodySendsEveryFieldTrimmedWithEmptyStringsToClear() {
        val body = required.copy(website = "  ", phone = " +1 416 ").toUpdate()
        assertEquals(
            CommunityUpdate(
                name = "Tibetan Association",
                description = "Losar, language classes.",
                website = "",
                phone = "+1 416",
                email = "hello@tat.example.org",
                contactPerson = ContactPerson(name = "Tashi", role = "", phone = "", email = ""),
                address = CommunityAddress(line1 = "", city = "Toronto", state = "", country = "Canada", postalCode = ""),
                socials = Socials(instagram = "", youtube = "", tiktok = "", facebook = ""),
            ),
            body,
        )
        val json = DrokpoJson.encodeToString(CommunityUpdate.serializer(), body)
        assertTrue(json, json.contains(""""website":""""))
        assertFalse(json, json.contains("null"))
    }

    // endregion

    // region Step machine

    @Test
    fun continueAndBackMoveBetweenTextSteps() = runTest(dispatcher) {
        val model = model()
        model.advance()
        assertEquals("blocked until valid", CommunityOnboardingStep.Basics, model.uiState.step)

        model.updateForm { copy(name = "TAT", communityDescription = "About") }
        model.advance()
        assertEquals(CommunityOnboardingStep.Contact, model.uiState.step)
        model.advance()
        assertEquals("email required", CommunityOnboardingStep.Contact, model.uiState.step)

        model.back()
        assertEquals(CommunityOnboardingStep.Basics, model.uiState.step)
        model.back()
        assertEquals("no step before Basics", CommunityOnboardingStep.Basics, model.uiState.step)
        assertEquals("form survives Back", "TAT", model.uiState.form.name)
        advanceUntilIdle()
        assertTrue(service.created.isEmpty())
    }

    @Test
    fun leavingAddressCreatesTheCommunity() = runTest(dispatcher) {
        val model = model()
        model.walkToAddress()
        model.advance()
        assertTrue(model.uiState.isSubmitting)
        advanceUntilIdle()

        assertEquals(listOf(required.toOnboardingIn()), service.created)
        assertEquals(CommunityOnboardingStep.Photos, model.uiState.step)
        assertFalse(model.uiState.isSubmitting)
        assertNull(model.uiState.errorMessage)
        assertEquals("Finish hasn't run yet", 0, refreshes)
    }

    @Test
    fun submittingIgnoresRepeatTapsAndBack() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        service.createGate = gate
        val model = model()
        model.walkToAddress()
        model.advance()
        advanceUntilIdle()
        assertTrue(model.uiState.isSubmitting)

        model.advance()
        model.back()
        assertEquals(CommunityOnboardingStep.Address, model.uiState.step)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, service.created.size)
        assertEquals(CommunityOnboardingStep.Photos, model.uiState.step)
    }

    @Test
    fun createFailureShowsTheBackendMessageAndRetriesThePost() = runTest(dispatcher) {
        service.createError = ApiError.Http(422, "Value error, must be an https:// URL")
        val model = model()
        model.walkToAddress()
        model.advance()
        advanceUntilIdle()

        assertEquals("Value error, must be an https:// URL", model.uiState.errorMessage)
        assertEquals(CommunityOnboardingStep.Address, model.uiState.step)
        assertFalse(model.uiState.isSubmitting)

        model.dismissError()
        assertNull(model.uiState.errorMessage)
        service.createError = null
        model.advance()
        advanceUntilIdle()
        assertEquals("nothing was created, so it's still a POST", 1, service.created.size)
        assertTrue(service.updated.isEmpty())
        assertEquals(CommunityOnboardingStep.Photos, model.uiState.step)
    }

    @Test
    fun goingBackAfterCreationPatchesInsteadOfRePosting() = runTest(dispatcher) {
        val model = model()
        createCommunity(model)

        model.back() // Address
        model.back() // Contact person
        model.back() // Contact
        model.updateForm { copy(website = "https://tat.example.org ", instagram = "") }
        repeat(2) { model.advance() }
        assertEquals(CommunityOnboardingStep.Address, model.uiState.step)
        model.advance()
        advanceUntilIdle()

        assertEquals("never re-POST (409)", 1, service.created.size)
        assertEquals(1, service.updated.size)
        val patch = service.updated.single()
        assertEquals("https://tat.example.org", patch.website)
        assertEquals("", patch.socials?.instagram)
        assertEquals("Tibetan Association", patch.name)
        assertEquals(CommunityOnboardingStep.Photos, model.uiState.step)

        // And again: every later pass over Address keeps PATCHing.
        model.back()
        model.advance()
        advanceUntilIdle()
        assertEquals(1, service.created.size)
        assertEquals(2, service.updated.size)
    }

    @Test
    fun patchFailureKeepsTheUserOnAddress() = runTest(dispatcher) {
        val model = model()
        createCommunity(model)
        model.back()
        service.updateError = ApiError.NotAuthenticated
        model.advance()
        advanceUntilIdle()
        assertEquals("You need to sign in again.", model.uiState.errorMessage)
        assertEquals(CommunityOnboardingStep.Address, model.uiState.step)
        assertFalse(model.uiState.isSubmitting)
    }

    @Test
    fun aLostCreateResponseRecoversByPatchingTheExistingCommunity() = runTest(dispatcher) {
        // An earlier POST landed but its response was lost, so this one 409s.
        service.createError = ApiError.Http(409, COMMUNITY_ALREADY_EXISTS)
        val model = model()
        model.walkToAddress()
        model.advance()
        advanceUntilIdle()

        assertEquals(1, service.createAttempts)
        assertEquals("the form is saved over the existing doc", listOf(required.toUpdate()), service.updated)
        assertEquals(CommunityOnboardingStep.Photos, model.uiState.step)
        assertNull(model.uiState.errorMessage)
        assertFalse(model.uiState.isSubmitting)

        model.back()
        model.advance()
        advanceUntilIdle()
        assertEquals("no new POST once the community is known to exist", 1, service.createAttempts)
        assertEquals(2, service.updated.size)
        assertEquals(CommunityOnboardingStep.Photos, model.uiState.step)
    }

    @Test
    fun aPersonAccountConflictIsAnErrorAndTheRetryStillPosts() = runTest(dispatcher) {
        val conflict = "This account is already registered as a person"
        service.createError = ApiError.Http(409, conflict)
        val model = model()
        model.walkToAddress()
        model.advance()
        advanceUntilIdle()

        assertEquals(conflict, model.uiState.errorMessage)
        assertEquals(CommunityOnboardingStep.Address, model.uiState.step)
        assertTrue("never PATCH a community that doesn't exist", service.updated.isEmpty())
        assertFalse(model.uiState.isSubmitting)

        model.dismissError()
        model.advance()
        advanceUntilIdle()
        assertEquals("still a POST", 2, service.createAttempts)
        assertTrue(service.updated.isEmpty())
        assertEquals(conflict, model.uiState.errorMessage)
        assertEquals(CommunityOnboardingStep.Address, model.uiState.step)
    }

    @Test
    fun aFailedRecoveryPatchKeepsTheUserOnAddressAndTheRetryPatches() = runTest(dispatcher) {
        service.createError = ApiError.Http(409, COMMUNITY_ALREADY_EXISTS)
        service.updateError = ApiError.Http(422, "Value error, must be an https:// URL")
        val model = model()
        model.walkToAddress()
        model.advance()
        advanceUntilIdle()

        assertEquals("Value error, must be an https:// URL", model.uiState.errorMessage)
        assertEquals(CommunityOnboardingStep.Address, model.uiState.step)
        assertFalse(model.uiState.isSubmitting)

        model.dismissError()
        service.updateError = null
        model.advance()
        advanceUntilIdle()
        assertEquals("the retry PATCHes, never re-POSTs", 1, service.createAttempts)
        assertEquals(listOf(required.toUpdate()), service.updated)
        assertEquals(CommunityOnboardingStep.Photos, model.uiState.step)
        assertNull(model.uiState.errorMessage)
    }

    // endregion

    // region Photos

    @Test
    fun pickedPhotosAppendInOrderAndCountLoadFailures() = runTest(dispatcher) {
        service.unloadable += "bad"
        val model = model()
        model.addPickedPhotos(listOf("a", "bad", "b"))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), model.uiState.photos.map { it.uri })
        assertEquals("One photo couldn't be loaded — try picking it again.", model.uiState.errorMessage)

        model.dismissError()
        service.unloadable += setOf("bad2", "bad3")
        model.addPickedPhotos(listOf("bad", "bad2", "bad3", "c"))
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), model.uiState.photos.map { it.uri })
        assertEquals("3 photos couldn't be loaded — try picking them again.", model.uiState.errorMessage)
    }

    @Test
    fun photoLoadFailureCopy() {
        assertNull(photoLoadFailureMessage(0))
        assertEquals("One photo couldn't be loaded — try picking it again.", photoLoadFailureMessage(1))
        assertEquals("2 photos couldn't be loaded — try picking them again.", photoLoadFailureMessage(2))
    }

    @Test
    fun neverMoreThanSixPhotos() = runTest(dispatcher) {
        val model = model()
        model.addPickedPhotos((1..8).map { "p$it" })
        advanceUntilIdle()
        assertEquals((1..6).map { "p$it" }, model.uiState.photos.map { it.uri })
        assertNull("extra picks are dropped, not failures", model.uiState.errorMessage)
    }

    @Test
    fun theSamePictureTwiceIsTwoPhotos() = runTest(dispatcher) {
        val model = model()
        model.addPickedPhotos(listOf("same", "same"))
        advanceUntilIdle()
        val photos = model.uiState.photos
        assertEquals(2, photos.size)
        assertTrue(photos[0].key != photos[1].key)

        model.removePhoto(photos[0])
        assertEquals(listOf(photos[1]), model.uiState.photos)
    }

    @Test
    fun finishUploadsEachPhotoInGridOrderThenRefreshesTheSession() = runTest(dispatcher) {
        val model = model()
        createCommunity(model)
        model.addPickedPhotos(listOf("logo", "hall", "losar"))
        advanceUntilIdle()

        model.advance()
        advanceUntilIdle()

        assertEquals(listOf("logo", "hall", "losar"), service.uploaded)
        assertEquals(listOf(0, 1, 2), service.confirmed.map { it.order })
        assertEquals(
            listOf("communities/c1/photos/1-logo.jpg", "communities/c1/photos/2-hall.jpg", "communities/c1/photos/3-losar.jpg"),
            service.confirmed.map { it.storagePath },
        )
        assertTrue(model.completed)
        assertEquals(1, refreshes)
        assertNull(model.uiState.errorMessage)
    }

    @Test
    fun finishingWithNoPhotosIsAllowed() = runTest(dispatcher) {
        val model = model()
        createCommunity(model)
        assertTrue(model.uiState.canAdvance)
        model.advance()
        advanceUntilIdle()
        assertTrue(service.uploaded.isEmpty())
        assertTrue(model.completed)
        assertEquals(1, refreshes)
    }

    @Test
    fun theSpinnerStaysUpWhileTheSessionReRoutes() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        refreshGate = gate
        val model = model()
        createCommunity(model)
        model.advance()
        advanceUntilIdle()
        assertEquals(1, refreshes)
        assertTrue(model.uiState.isSubmitting)

        model.advance()
        advanceUntilIdle()
        assertEquals("no second Finish while re-routing", 1, refreshes)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.uiState.isSubmitting)
    }

    @Test
    fun aRetryAfterAMidBatchFailureResumesByIdentityNotPosition() = runTest(dispatcher) {
        val model = model()
        createCommunity(model)
        model.addPickedPhotos(listOf("a", "b", "c"))
        advanceUntilIdle()
        service.failingUploads += "b"

        model.advance()
        advanceUntilIdle()
        assertEquals("That photo couldn't be processed. Try a different one.", model.uiState.errorMessage)
        assertEquals(listOf("a"), service.uploaded)
        assertFalse(model.completed)
        assertEquals("no refresh after a failure", 0, refreshes)
        assertFalse(model.uiState.isSubmitting)

        // The user edits the grid before retrying: drop the already-uploaded
        // "a" and add "d". Position-based resuming would now skip "b".
        model.dismissError()
        service.failingUploads.clear()
        model.removePhoto(model.uiState.photos.first { it.uri == "a" })
        model.addPickedPhotos(listOf("d"))
        advanceUntilIdle()

        model.advance()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c", "d"), service.uploaded)
        assertEquals(
            listOf("a" to 0, "b" to 0, "c" to 1, "d" to 2),
            service.confirmed.map { it.storagePath.substringAfter('-').removeSuffix(".jpg") to it.order },
        )
        assertEquals("the confirmed-then-removed \"a\" is deleted", listOf("communities/c1/photos/1-a.jpg"), service.deleted)
        assertTrue(model.completed)
        assertEquals(1, refreshes)
    }

    @Test
    fun aRetryNeverReUploadsConfirmedPhotos() = runTest(dispatcher) {
        val model = model()
        createCommunity(model)
        model.addPickedPhotos(listOf("a", "b"))
        advanceUntilIdle()
        service.failingUploads += "b"
        model.advance()
        advanceUntilIdle()
        service.failingUploads.clear()
        model.dismissError()

        model.advance()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), service.uploaded)
        assertEquals(listOf(0, 1), service.confirmed.map { it.order })
    }

    /** Picks "a" and "b", then a Finish confirms "a" and fails on "b". */
    private fun TestScope.confirmOnlyTheFirstOfTwo(model: CommunityOnboardingModel) {
        createCommunity(model)
        model.addPickedPhotos(listOf("a", "b"))
        advanceUntilIdle()
        service.failingUploads += "b"
        model.advance()
        advanceUntilIdle()
        assertEquals(listOf("upload:a"), service.photoCalls)
        assertFalse(model.completed)
        model.dismissError()
        service.failingUploads.clear()
    }

    @Test
    fun finishDeletesAConfirmedPhotoRemovedFromTheGridBeforeUploading() = runTest(dispatcher) {
        val model = model()
        confirmOnlyTheFirstOfTwo(model)

        model.removePhoto(model.uiState.photos.first { it.uri == "a" })
        model.advance()
        advanceUntilIdle()

        assertEquals(
            listOf("upload:a", "delete:communities/c1/photos/1-a.jpg", "upload:b"),
            service.photoCalls,
        )
        assertEquals("\"b\" is now the logo", listOf(0, 0), service.confirmed.map { it.order })
        assertTrue(model.completed)
        assertEquals(1, refreshes)
        assertNull(model.uiState.errorMessage)
    }

    @Test
    fun aFailedDeleteStopsFinishAndTheNextFinishRetriesIt() = runTest(dispatcher) {
        val model = model()
        confirmOnlyTheFirstOfTwo(model)

        service.deleteError = ApiError.Http(500, "Internal Server Error")
        model.removePhoto(model.uiState.photos.first { it.uri == "a" })
        model.advance()
        advanceUntilIdle()
        assertEquals("Internal Server Error", model.uiState.errorMessage)
        assertFalse(model.completed)
        assertEquals(0, refreshes)
        assertFalse(model.uiState.isSubmitting)
        assertEquals("nothing uploads past a failed delete", listOf("upload:a"), service.photoCalls)

        model.dismissError()
        service.deleteError = null
        model.advance()
        advanceUntilIdle()
        assertEquals(
            listOf("upload:a", "delete:communities/c1/photos/1-a.jpg", "upload:b"),
            service.photoCalls,
        )
        assertTrue(model.completed)
        assertEquals(1, refreshes)
    }

    @Test
    fun finishWaitsForPicksStillLoadingAndUploadsThemInGridOrder() = runTest(dispatcher) {
        val model = model()
        createCommunity(model)
        model.addPickedPhotos(listOf("a"))
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        service.loadGate = gate

        model.addPickedPhotos(listOf("late"))
        model.advance()
        advanceUntilIdle()
        assertTrue("nothing uploads while a pick is loading", service.uploaded.isEmpty())
        assertTrue(model.uiState.isSubmitting)
        assertEquals(0, refreshes)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a", "late"), service.uploaded)
        assertEquals(listOf(0, 1), service.confirmed.map { it.order })
        assertTrue(model.completed)
        assertEquals(1, refreshes)
        assertNull(model.uiState.errorMessage)
    }

    @Test
    fun finishStopsWhenAPickStillLoadingFailsToLoad() = runTest(dispatcher) {
        val model = model()
        createCommunity(model)
        val gate = CompletableDeferred<Unit>()
        service.loadGate = gate
        service.unloadable += "late"

        model.addPickedPhotos(listOf("late"))
        model.advance()
        advanceUntilIdle()
        assertTrue(model.uiState.isSubmitting)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("One photo couldn't be loaded — try picking it again.", model.uiState.errorMessage)
        assertTrue(service.uploaded.isEmpty())
        assertFalse(model.completed)
        assertEquals(0, refreshes)
        assertFalse(model.uiState.isSubmitting)

        // The failed load doesn't block a later Finish.
        model.dismissError()
        model.advance()
        advanceUntilIdle()
        assertTrue(model.completed)
        assertEquals(1, refreshes)
    }

    // endregion
}
