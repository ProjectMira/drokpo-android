package app.drokpo.android.features.onboarding

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.PhotoUploaderError
import app.drokpo.android.core.model.GeoLocation
import app.drokpo.android.core.model.OnboardingIn
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.PhotoConfirm
import app.drokpo.android.core.model.ProfileUpdate
import app.drokpo.android.core.model.Vocabulary
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class OnboardingModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region Fakes

    private class FakeApi : OnboardingApi {
        val calls = mutableListOf<String>()
        val created = mutableListOf<OnboardingIn>()
        val updated = mutableListOf<ProfileUpdate>()
        val confirmed = mutableListOf<PhotoConfirm>()

        /** Thrown (once each) by the matching call, in order. */
        val createErrors = ArrayDeque<Exception>()
        val confirmErrors = mutableMapOf<Int, Exception>() // confirm call number (1-based) → error
        val completeErrors = ArrayDeque<Exception>()
        val deleteErrors = ArrayDeque<Exception>()
        val attachedErrors = ArrayDeque<Exception>()
        var createGate: CompletableDeferred<Unit>? = null
        /** What GET /api/profile/me reports as attached. */
        var attached: List<Photo> = emptyList()
        private var confirmCalls = 0

        override suspend fun createProfile(body: OnboardingIn) {
            calls += "create"
            createGate?.await()
            createErrors.removeFirstOrNull()?.let { throw it }
            created += body
        }

        override suspend fun updateProfile(body: ProfileUpdate) {
            calls += "update"
            updated += body
        }

        override suspend fun confirmPhoto(body: PhotoConfirm) {
            calls += "confirm:${body.order}"
            confirmCalls += 1
            confirmErrors.remove(confirmCalls)?.let { throw it }
            confirmed += body
        }

        override suspend fun complete() {
            calls += "complete"
            completeErrors.removeFirstOrNull()?.let { throw it }
        }

        override suspend fun deletePhoto(storagePath: String) {
            calls += "delete:$storagePath"
            deleteErrors.removeFirstOrNull()?.let { throw it }
        }

        override suspend fun attachedPhotos(): List<Photo> {
            calls += "attached"
            attachedErrors.removeFirstOrNull()?.let { throw it }
            return attached
        }
    }

    private class FakeLocation(
        var granted: Boolean = false,
        var grantOnRequest: Boolean = true,
        var fix: GeoLocation? = GeoLocation(lat = 32.2, lng = 76.3),
    ) : LocationPlatform {
        var permissionRequests = 0
        var fixRequests = 0
        override fun hasPermission() = granted
        override suspend fun requestPermission(): Boolean {
            permissionRequests += 1
            if (grantOnRequest) granted = true
            return granted
        }
        override fun canAskAgain() = true
        override suspend fun currentLocation(): GeoLocation? {
            fixRequests += 1
            return fix
        }
    }

    private class Harness(initial: OnboardingDraft? = null) {
        val api = FakeApi()
        val uploads = mutableListOf<String>()
        val uploadErrors = mutableMapOf<String, Exception>() // uri → error (once)
        var refreshes = 0
        var refreshGate: CompletableDeferred<Unit>? = null
        val refreshErrors = ArrayDeque<Exception>()
        val location = FakeLocation()
        val fetcher = LocationFetcher(location)
        val model = OnboardingModel(
            api = api,
            uploadPhoto = { uri ->
                uploads += uri
                uploadErrors.remove(uri)?.let { throw it }
                "users/u1/photos/${uri.substringAfterLast('/')}.jpg"
            },
            refreshProfile = {
                refreshes += 1
                refreshGate?.await()
                refreshErrors.removeFirstOrNull()?.let { throw it }
            },
            today = LocalDate.of(2026, 10, 7),
            initial = initial,
        )

        fun fillToLocation() {
            model.onEdit(OnboardingEdit.DisplayName(" Pema "))
            model.onEdit(OnboardingEdit.Gender("male"))
            model.advance(fetcher)
            model.onEdit(OnboardingEdit.Region("Nepal"))
            model.onEdit(OnboardingEdit.ToggleLanguage("Tibetan"))
            model.advance(fetcher)
            model.advance(fetcher) // About you: optional
            model.onEdit(OnboardingEdit.AcceptedTerms(true))
            model.advance(fetcher)
        }

        fun TestScope.fillToPhotos(photoCount: Int) {
            fillToLocation()
            model.advance(fetcher)
            advanceUntilIdle()
            model.onEdit(OnboardingEdit.AddPhotos((1..photoCount).map { "content://p$it" }))
        }
    }

    // endregion

    // region Local steps

    @Test
    fun firstFourStepsAdvanceLocallyOnlyWhenValid() = runTest {
        val h = Harness()
        h.model.advance(h.fetcher) // empty Basics: ignored
        assertEquals(OnboardingStep.Basics, h.model.state.step)

        h.fillToLocation()
        assertEquals(OnboardingStep.Location, h.model.state.step)
        assertTrue(h.api.calls.isEmpty())
        assertEquals(0, h.location.permissionRequests)
    }

    @Test
    fun backStepsBackButNotWhileSubmitting() = runTest {
        val h = Harness()
        h.fillToLocation()
        h.model.back()
        assertEquals(OnboardingStep.Socials, h.model.state.step)
        h.model.advance(h.fetcher)

        h.api.createGate = CompletableDeferred()
        h.model.advance(h.fetcher)
        runCurrent()
        assertTrue(h.model.state.isSubmitting)
        h.model.back()
        assertEquals(OnboardingStep.Location, h.model.state.step)
        h.api.createGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(OnboardingStep.Photos, h.model.state.step)
    }

    // endregion

    // region Location → create profile

    @Test
    fun continueOnLocationAsksForLocationThenCreatesTheProfile() = runTest {
        val h = Harness()
        h.fillToLocation()
        h.model.advance(h.fetcher)
        assertTrue(h.model.state.isSubmitting) // spinner from the tap on
        advanceUntilIdle()

        assertEquals(1, h.location.permissionRequests)
        assertEquals(GeoLocation(lat = 32.2, lng = 76.3), h.model.state.location)
        assertEquals(listOf("create"), h.api.calls)
        val body = h.api.created.single()
        assertEquals("Pema", body.displayName)
        assertEquals("2001-10-07", body.dob)
        assertEquals(GeoLocation(lat = 32.2, lng = 76.3), body.location)
        assertEquals(OnboardingStep.Photos, h.model.state.step)
        assertFalse(h.model.state.isSubmitting)
    }

    @Test
    fun deniedLocationFallsBackToTheRegionCentre() = runTest {
        val h = Harness()
        h.location.grantOnRequest = false
        h.fillToLocation()
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertNull(h.model.state.location)
        assertEquals(0, h.location.fixRequests)
        assertEquals(Vocabulary.regionCoordinates.getValue("Nepal"), h.api.created.single().location)
        assertEquals(OnboardingStep.Photos, h.model.state.step)
    }

    @Test
    fun failedCreateShowsTheErrorAndRetryReusesTheSavedLocation() = runTest {
        val h = Harness()
        h.api.createErrors += ApiError.Http(500, "Backend exploded")
        h.fillToLocation()
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertEquals("Backend exploded", h.model.state.errorMessage)
        assertEquals(OnboardingStep.Location, h.model.state.step)
        assertFalse(h.model.state.isSubmitting)

        h.model.dismissError()
        assertNull(h.model.state.errorMessage)
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals(1, h.location.permissionRequests) // not asked again
        assertEquals(1, h.location.fixRequests)
        assertEquals(OnboardingStep.Photos, h.model.state.step)
    }

    @Test
    fun networkErrorsUseTheFoundationMessage() = runTest {
        val h = Harness()
        h.api.createErrors += IOException("socket closed")
        h.fillToLocation()
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals("The network connection was lost.", h.model.state.errorMessage)
    }

    @Test
    fun existingProfileIsUpdatedInsteadOfStrandingThePerson() = runTest {
        val h = Harness()
        h.api.createErrors += ApiError.Http(409, "Profile already exists")
        h.fillToLocation()
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertEquals(listOf("create", "update", "attached"), h.api.calls)
        val update = h.api.updated.single()
        assertEquals("Pema", update.displayName)
        assertEquals("Nepal", update.region)
        assertEquals(GeoLocation(lat = 32.2, lng = 76.3), update.location)
        assertNull(h.model.state.errorMessage)
        assertEquals(OnboardingStep.Photos, h.model.state.step)
    }

    @Test
    fun otherConflictsStillSurface() = runTest {
        val h = Harness()
        h.api.createErrors += ApiError.Http(409, "This account is already registered as a community")
        h.fillToLocation()
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertEquals(listOf("create"), h.api.calls)
        assertEquals("This account is already registered as a community", h.model.state.errorMessage)
        assertEquals(OnboardingStep.Location, h.model.state.step)
    }

    @Test
    fun aDoubleTapStartsOnlyOneSubmission() = runTest {
        val h = Harness()
        h.fillToLocation()
        h.api.createGate = CompletableDeferred()
        h.model.advance(h.fetcher)
        h.model.advance(h.fetcher)
        runCurrent()
        h.model.advance(h.fetcher)
        h.api.createGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("create"), h.api.calls)
        assertEquals(1, h.location.permissionRequests)
    }

    // endregion

    // region Photos → complete

    @Test
    fun finishUploadsInPickOrderThenCompletesAndRefreshes() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(3) }
        h.api.calls.clear()

        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertEquals(listOf("content://p1", "content://p2", "content://p3"), h.uploads)
        assertEquals(
            listOf(
                PhotoConfirm("users/u1/photos/p1.jpg", 0),
                PhotoConfirm("users/u1/photos/p2.jpg", 1),
                PhotoConfirm("users/u1/photos/p3.jpg", 2),
            ),
            h.api.confirmed,
        )
        assertEquals(listOf("confirm:0", "confirm:1", "confirm:2", "complete"), h.api.calls)
        assertTrue(h.model.state.completed)
        assertEquals(1, h.refreshes)
        // RootScreen crossfades away; Finish must not turn tappable meanwhile.
        assertTrue(h.model.state.isSubmitting)
    }

    @Test
    fun theSpinnerStaysUpThroughTheRefreshSoFinishCantRunTwice() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(1) }
        h.api.calls.clear()
        h.refreshGate = CompletableDeferred()

        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertTrue(h.model.state.completed)
        assertTrue(h.model.state.isSubmitting) // refresh still in flight
        h.model.advance(h.fetcher)
        h.model.back()
        h.model.onEdit(OnboardingEdit.RemovePhoto(h.model.state.photos[0].id))
        advanceUntilIdle()

        h.refreshGate?.complete(Unit)
        advanceUntilIdle()
        // Still up after the refresh: the screen fades out under it.
        assertTrue(h.model.state.isSubmitting)
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals(listOf("confirm:0", "complete"), h.api.calls)
        assertEquals(1, h.refreshes)
        assertEquals(OnboardingStep.Photos, h.model.state.step)
        assertEquals(1, h.model.state.photos.size)
    }

    @Test
    fun aFailedRefreshRetriesOnlyTheRefresh() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(1) }
        h.api.calls.clear()
        h.refreshErrors += IOException("socket closed")

        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertTrue(h.model.state.completed)
        assertFalse(h.model.state.isSubmitting)
        assertEquals("The network connection was lost.", h.model.state.errorMessage)

        h.model.dismissError()
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals(listOf("confirm:0", "complete"), h.api.calls)
        assertEquals(2, h.refreshes)
    }

    @Test
    fun retryAfterAFailedConfirmResumesWithoutReuploading() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(3) }
        h.api.calls.clear()
        h.api.confirmErrors[2] = ApiError.Http(503, "Try again")

        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals("Try again", h.model.state.errorMessage)
        assertFalse(h.model.state.completed)
        assertEquals(0, h.refreshes)
        assertEquals(listOf(true, false, false), h.model.state.photos.map { it.confirmed })
        // Photo 2 made it to Storage; only its confirm failed.
        assertEquals("users/u1/photos/p2.jpg", h.model.state.photos[1].storagePath)

        h.model.dismissError()
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        // Each photo was uploaded exactly once; photo 2 was confirmed again with the same path.
        assertEquals(listOf("content://p1", "content://p2", "content://p3"), h.uploads)
        assertEquals(listOf("confirm:0", "confirm:1", "confirm:1", "confirm:2", "complete"), h.api.calls)
        assertTrue(h.model.state.completed)
        assertEquals(1, h.refreshes)
    }

    @Test
    fun retryAfterAFailedUploadSkipsConfirmedPhotos() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(3) }
        h.api.calls.clear()
        h.uploadErrors["content://p2"] = PhotoUploaderError.InvalidImage()

        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals("That photo couldn't be processed. Try a different one.", h.model.state.errorMessage)
        assertEquals(listOf("confirm:0"), h.api.calls)

        // The person swaps the bad photo for another one, then retries.
        h.model.dismissError()
        h.model.onEdit(OnboardingEdit.RemovePhoto(h.model.state.photos[1].id))
        h.model.onEdit(OnboardingEdit.AddPhotos(listOf("content://p4")))
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertEquals(listOf("content://p1", "content://p2", "content://p3", "content://p4"), h.uploads)
        assertEquals(
            listOf(
                PhotoConfirm("users/u1/photos/p1.jpg", 0),
                PhotoConfirm("users/u1/photos/p3.jpg", 1),
                PhotoConfirm("users/u1/photos/p4.jpg", 2),
            ),
            h.api.confirmed,
        )
        assertTrue(h.model.state.completed)
    }

    @Test
    fun removingAnAttachedPhotoDeletesItBeforeTheRetry() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(3) }
        h.api.calls.clear()
        h.uploadErrors["content://p2"] = IOException("socket closed")

        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals(listOf("confirm:0"), h.api.calls) // p1 is on the profile now

        // The person drops p1 after all and retries.
        h.model.dismissError()
        h.model.onEdit(OnboardingEdit.RemovePhoto(h.model.state.photos[0].id))
        assertEquals(listOf("users/u1/photos/p1.jpg"), h.model.state.pendingPhotoDeletes)
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        // p1 is detached first, so p2 becomes the main photo.
        assertEquals(
            listOf("confirm:0", "delete:users/u1/photos/p1.jpg", "confirm:0", "confirm:1", "complete"),
            h.api.calls,
        )
        assertEquals(
            listOf(PhotoConfirm("users/u1/photos/p2.jpg", 0), PhotoConfirm("users/u1/photos/p3.jpg", 1)),
            h.api.confirmed.drop(1),
        )
        assertEquals(listOf("content://p1", "content://p2", "content://p2", "content://p3"), h.uploads)
        assertTrue(h.model.state.pendingPhotoDeletes.isEmpty())
        assertTrue(h.model.state.completed)
    }

    @Test
    fun removingAnUploadedButUnconfirmedPhotoDeletesItsFile() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(3) }
        h.api.calls.clear()
        h.api.confirmErrors[2] = ApiError.Http(503, "Try again")

        h.model.advance(h.fetcher)
        advanceUntilIdle()
        h.model.dismissError()
        h.model.onEdit(OnboardingEdit.RemovePhoto(h.model.state.photos[1].id))
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertEquals(
            listOf("confirm:0", "confirm:1", "delete:users/u1/photos/p2.jpg", "confirm:1", "complete"),
            h.api.calls,
        )
        assertEquals(PhotoConfirm("users/u1/photos/p3.jpg", 1), h.api.confirmed.last())
    }

    @Test
    fun aFailedDeleteStopsFinishAndIsRetried() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(2) }
        h.api.calls.clear()
        h.uploadErrors["content://p2"] = IOException("socket closed")
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        h.model.dismissError()
        h.model.onEdit(OnboardingEdit.RemovePhoto(h.model.state.photos[0].id))

        h.api.deleteErrors += ApiError.Http(503, "Try again")
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals("Try again", h.model.state.errorMessage)
        assertEquals(listOf("users/u1/photos/p1.jpg"), h.model.state.pendingPhotoDeletes)
        assertEquals(listOf("confirm:0", "delete:users/u1/photos/p1.jpg"), h.api.calls)

        h.model.dismissError()
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals(
            listOf(
                "confirm:0",
                "delete:users/u1/photos/p1.jpg",
                "delete:users/u1/photos/p1.jpg",
                "confirm:0",
                "complete",
            ),
            h.api.calls,
        )
        assertTrue(h.model.state.completed)
    }

    @Test
    fun photosAlreadyOnAnExistingProfileShowAndNewOnesFollowThem() = runTest {
        val h = Harness()
        h.api.createErrors += ApiError.Http(409, "Profile already exists")
        h.api.attached = listOf(
            Photo("users/u1/photos/old1.jpg", 0, "https://cdn/old1.jpg"),
            Photo("users/u1/photos/old2.jpg", 1, "https://cdn/old2.jpg"),
        )
        h.fillToLocation()
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertEquals(listOf("create", "update", "attached"), h.api.calls)
        assertEquals(OnboardingStep.Photos, h.model.state.step)
        val shown = h.model.state.photos
        assertEquals(listOf("https://cdn/old1.jpg", "https://cdn/old2.jpg"), shown.map { it.uri })
        assertTrue(shown.all { it.confirmed })
        assertEquals(4, h.model.state.remainingPhotoSlots)
        assertTrue(h.model.state.canAdvance) // already enough to finish

        h.model.onEdit(OnboardingEdit.AddPhotos(listOf("content://p1", "content://p2")))
        h.api.calls.clear()
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertEquals(listOf("content://p1", "content://p2"), h.uploads)
        assertEquals(listOf("confirm:2", "confirm:3", "complete"), h.api.calls)
        assertTrue(h.model.state.completed)
    }

    @Test
    fun backThenContinueKeepsThisRunsConfirmedPhotos() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(2) }
        h.uploadErrors["content://p2"] = IOException("socket closed")
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        h.model.dismissError()
        val p1 = h.model.state.photos[0]

        // Back to Location and on again: the profile exists now.
        h.model.back()
        h.api.createErrors += ApiError.Http(409, "Profile already exists")
        h.api.attached = listOf(Photo(p1.storagePath!!, 0, "https://cdn/p1.jpg"))
        h.api.calls.clear()
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertEquals(listOf("create", "update", "attached"), h.api.calls)
        // Same tiles (ids and picker URIs), nothing doubled.
        assertEquals(listOf(p1.id, p1.id + 1), h.model.state.photos.map { it.id })
        assertEquals(listOf("content://p1", "content://p2"), h.model.state.photos.map { it.uri })
        assertEquals(listOf(true, false), h.model.state.photos.map { it.confirmed })
    }

    @Test
    fun aFailedAttachedPhotosReadKeepsThePersonOnLocation() = runTest {
        val h = Harness()
        h.api.createErrors += ApiError.Http(409, "Profile already exists")
        h.api.attachedErrors += IOException("socket closed")
        h.fillToLocation()
        h.model.advance(h.fetcher)
        advanceUntilIdle()

        assertEquals("The network connection was lost.", h.model.state.errorMessage)
        assertEquals(OnboardingStep.Location, h.model.state.step)
        assertFalse(h.model.state.isSubmitting)

        // Retry: the POST still says 409, so the form is saved again and the read retried.
        h.model.dismissError()
        h.api.createErrors += ApiError.Http(409, "Profile already exists")
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals(listOf("create", "update", "attached", "create", "update", "attached"), h.api.calls)
        assertEquals(OnboardingStep.Photos, h.model.state.step)
    }

    @Test
    fun photoEditsAreRefusedWhileFinishRuns() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(2) }
        h.refreshGate = CompletableDeferred()
        h.model.advance(h.fetcher)
        h.model.onEdit(OnboardingEdit.AddPhotos(listOf("content://late")))
        h.model.onEdit(OnboardingEdit.RemovePhoto(h.model.state.photos[0].id))
        assertEquals(listOf("content://p1", "content://p2"), h.model.state.photos.map { it.uri })
        assertTrue(h.model.state.pendingPhotoDeletes.isEmpty())
        h.refreshGate?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun failedCompleteRetriesOnlyTheCompleteCall() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(2) }
        h.api.calls.clear()
        h.api.completeErrors += ApiError.Http(400, "At least one photo is required to complete onboarding")

        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals("At least one photo is required to complete onboarding", h.model.state.errorMessage)
        assertEquals(0, h.refreshes)

        h.model.dismissError()
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals(listOf("confirm:0", "confirm:1", "complete", "complete"), h.api.calls)
        assertEquals(2, h.uploads.size)
        assertEquals(1, h.refreshes)
    }

    @Test
    fun aRestoredDraftResumesWhereThePersonWas() = runTest {
        val saved = OnboardingState.initial(LocalDate.of(2026, 10, 6)).copy(
            step = OnboardingStep.Photos,
            displayName = "Pema",
            gender = "male",
            region = "Nepal",
            languages = setOf("Tibetan"),
            acceptedTerms = true,
            location = GeoLocation(lat = 32.2, lng = 76.3),
        ).toDraft(uid = "u1")
        val h = Harness(initial = OnboardingDraft.decode(saved.encode()))

        assertEquals(OnboardingStep.Location, h.model.state.step)
        assertEquals("Pema", h.model.state.displayName)
        assertEquals(LocalDate.of(2001, 10, 6), h.model.state.dob)
        assertEquals(LocalDate.of(2008, 10, 7), h.model.state.latestAllowedDob) // today's bound

        // The saved fix is reused: no second permission dialog.
        h.api.createErrors += ApiError.Http(409, "Profile already exists")
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertEquals(0, h.location.permissionRequests)
        assertEquals(GeoLocation(lat = 32.2, lng = 76.3), h.api.updated.single().location)
        assertEquals(OnboardingStep.Photos, h.model.state.step)
    }

    @Test
    fun finishNeedsAPhoto() = runTest {
        val h = Harness()
        with(h) { fillToPhotos(0) }
        h.api.calls.clear()
        h.model.advance(h.fetcher)
        advanceUntilIdle()
        assertTrue(h.api.calls.isEmpty())
        assertFalse(h.model.state.isSubmitting)
    }

    // endregion
}
