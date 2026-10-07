@file:OptIn(ExperimentalCoroutinesApi::class)

package app.drokpo.android.features.profile

import app.drokpo.android.core.ApiError
import app.drokpo.android.core.PhotoUploaderError
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.ProfileUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileModelTest {
    @get:Rule
    val main = ProfileMainDispatcherRule()

    private val a = photo("a", 0)
    private val b = photo("b", 1)
    private val c = photo("c", 2)
    private val profile = Profile(uid = "me", displayName = "Tenzin", photos = listOf(a, b, c), discoverable = true)

    private val session = MutableStateFlow<Profile?>(profile)

    /** What the next refreshProfile() publishes (null = the profile is unchanged). */
    private var serverProfile: Profile? = null
    private var refreshes = 0
    private val api = FakeProfileApi()

    private fun model() = ProfileModel(
        myProfile = session,
        refreshProfile = {
            refreshes++
            serverProfile?.let { session.value = it }
        },
        email = { "tenzin@example.com" },
        api = api,
    )

    @Test
    fun mirrorsServerPhotosAndEmail() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        assertEquals(listOf(a, b, c), model.state.value.orderedPhotos)
        assertEquals("tenzin@example.com", model.state.value.email)

        session.value = profile.copy(photos = listOf(c, a))
        advanceUntilIdle()
        assertEquals(listOf(c, a), model.state.value.orderedPhotos)
    }

    @Test
    fun dragReordersLocallyAndCommitsOnDrop() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.beginDrag(a.id)
        model.dragOver(c.id)
        assertEquals(listOf(b, c, a), model.state.value.orderedPhotos)
        assertTrue(api.calls.isEmpty())

        serverProfile = profile.copy(photos = listOf(b, c, a))
        model.endDrag()
        advanceUntilIdle()
        assertEquals(listOf("reorder ${b.storagePath},${c.storagePath},${a.storagePath}"), api.calls)
        assertEquals(1, refreshes)
        assertNull(model.state.value.draggingPhotoId)
        assertEquals(listOf(b, c, a), model.state.value.orderedPhotos)
    }

    @Test
    fun refreshMidDragDoesNotClobberTheLocalOrder() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.beginDrag(c.id)
        model.dragOver(a.id)
        assertEquals(listOf(c, a, b), model.state.value.orderedPhotos)

        // e.g. a pull-to-refresh lands while the finger is still down
        session.value = profile.copy(displayName = "Tenzin D")
        advanceUntilIdle()
        model.syncPhotos() // an onAppear mid-drag is ignored too
        assertEquals(listOf(c, a, b), model.state.value.orderedPhotos)
        assertEquals("Tenzin D", model.state.value.profile?.displayName)
    }

    @Test
    fun refreshesWhileTheReorderSavesKeepTheNewOrder() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        model.beginDrag(a.id)
        model.dragOver(c.id)
        model.endDrag()
        advanceUntilIdle()
        assertEquals(listOf("reorder ${b.storagePath},${c.storagePath},${a.storagePath}"), api.calls)

        // The PATCH is still open; the server profile still has the old order.
        session.value = profile.copy(discoverable = false) // e.g. the Discover switch's refresh
        advanceUntilIdle()
        model.refresh() // pull-to-refresh
        advanceUntilIdle()
        model.syncPhotos() // onAppear
        assertEquals(listOf(b, c, a), model.state.value.orderedPhotos)
        assertEquals(false, model.state.value.profile?.discoverable)

        // The save lands: the strip follows the server from here on.
        serverProfile = profile.copy(discoverable = false, photos = listOf(b, c, a))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(b, c, a), model.state.value.orderedPhotos)
        session.value = profile.copy(photos = listOf(c, b, a))
        advanceUntilIdle()
        assertEquals(listOf(c, b, a), model.state.value.orderedPhotos)
    }

    @Test
    fun uploadFinishingWhileTheReorderSavesWaitsForIt() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        model.beginDrag(a.id)
        model.dragOver(c.id)
        model.endDrag()
        advanceUntilIdle()

        val newPhoto = photo("new", 3)
        api.gate = null // the upload's confirm goes straight through
        serverProfile = profile.copy(photos = listOf(a, b, c, newPhoto)) // old order + the upload
        model.addPhotoFrom { newPhoto.storagePath }
        advanceUntilIdle()
        assertEquals(listOf(b, c, a), model.state.value.orderedPhotos)

        serverProfile = profile.copy(photos = listOf(b, c, a, newPhoto))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(b, c, a, newPhoto), model.state.value.orderedPhotos)
    }

    @Test
    fun unchangedOrderIsNotSent() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.beginDrag(a.id)
        model.dragOver(b.id)
        model.dragOver(b.id) // moving back onto the same neighbour restores the order
        assertEquals(listOf(a, b, c), model.state.value.orderedPhotos)
        model.endDrag()
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
        assertEquals(0, refreshes)
        assertNull(model.state.value.draggingPhotoId)
    }

    @Test
    fun failedReorderAlertsAndRevertsToServerOrder() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        api.failure = ApiError.Http(400, "storagePaths must match the profile's photos")
        model.beginDrag(a.id)
        model.dragOver(c.id)
        model.endDrag()
        advanceUntilIdle()
        assertEquals("storagePaths must match the profile's photos", model.state.value.errorMessage)
        assertEquals(1, refreshes)
        // The refresh returned an identical profile (no emission) — the order must still revert.
        assertEquals(listOf(a, b, c), model.state.value.orderedPhotos)

        model.dismissError()
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun endDragWithoutDragIsNoOp() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.endDrag()
        model.dragOver(b.id)
        advanceUntilIdle()
        assertEquals(listOf(a, b, c), model.state.value.orderedPhotos)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun addPhotoUploadsConfirmsAtTheEndAndRefreshes() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        val upload = CompletableDeferred<String>()
        val newPhoto = photo("new", 3)
        serverProfile = profile.copy(photos = listOf(a, b, c, newPhoto))
        model.addPhotoFrom { upload.await() }
        assertTrue(model.state.value.isWorking)

        upload.complete(newPhoto.storagePath)
        advanceUntilIdle()
        assertEquals(listOf("confirm ${newPhoto.storagePath} @3"), api.calls)
        assertEquals(1, refreshes)
        assertFalse(model.state.value.isWorking)
        assertEquals(listOf(a, b, c, newPhoto), model.state.value.orderedPhotos)
    }

    @Test
    fun unreadableImageShowsUploaderMessage() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        model.addPhotoFrom { throw PhotoUploaderError.InvalidImage() }
        advanceUntilIdle()
        assertEquals("That photo couldn't be processed. Try a different one.", model.state.value.errorMessage)
        assertTrue(api.calls.isEmpty())
        assertEquals(0, refreshes)
        assertFalse(model.state.value.isWorking)
    }

    @Test
    fun deletePhotoDeletesByStoragePathAndRefreshes() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        serverProfile = profile.copy(photos = listOf(a, c))
        model.deletePhoto(b)
        assertTrue(model.state.value.isWorking)
        advanceUntilIdle()
        assertEquals(listOf("delete ${b.storagePath}"), api.calls)
        assertEquals(listOf(a, c), model.state.value.orderedPhotos)
        assertFalse(model.state.value.isWorking)
    }

    @Test
    fun deleteFailureAlerts() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        api.failure = ApiError.Http(403, "")
        model.deletePhoto(b)
        advanceUntilIdle()
        assertEquals("Server error (403).", model.state.value.errorMessage)
        assertEquals(0, refreshes)
    }

    @Test
    fun discoverableSwitchIsOptimisticThenFollowsTheServer() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        serverProfile = profile.copy(discoverable = false)

        model.setDiscoverable(false)
        advanceUntilIdle()
        assertEquals(false, model.state.value.pendingDiscoverable)
        assertFalse(model.state.value.isDiscoverable)
        assertFalse(model.state.value.canToggleDiscoverable)
        model.setDiscoverable(true) // disabled while saving
        assertEquals(listOf(ProfileUpdate(discoverable = false)), api.updates)

        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(model.state.value.pendingDiscoverable)
        assertFalse(model.state.value.isDiscoverable)
        assertEquals(1, refreshes)
    }

    @Test
    fun discoverableFailureRollsBack() = runTest(main.dispatcher) {
        val model = model()
        advanceUntilIdle()
        api.failure = ApiError.NotAuthenticated
        model.setDiscoverable(false)
        advanceUntilIdle()
        assertNull(model.state.value.pendingDiscoverable)
        assertTrue(model.state.value.isDiscoverable)
        assertEquals("You need to sign in again.", model.state.value.errorMessage)
        assertEquals(0, refreshes)
    }

    @Test
    fun discoverableDefaultsToShownAndNeedsAProfile() = runTest(main.dispatcher) {
        session.value = null
        val model = model()
        advanceUntilIdle()
        assertTrue(model.state.value.isDiscoverable)
        assertFalse(model.state.value.canToggleDiscoverable)
        model.setDiscoverable(false)
        advanceUntilIdle()
        assertTrue(api.updates.isEmpty())

        session.value = profile.copy(discoverable = null)
        advanceUntilIdle()
        assertTrue(model.state.value.isDiscoverable)
        assertTrue(model.state.value.canToggleDiscoverable)
    }

    @Test
    fun pullToRefreshTracksProgress() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val model = ProfileModel(
            myProfile = session,
            refreshProfile = {
                refreshes++
                gate.await()
            },
            email = { null },
            api = api,
        )
        advanceUntilIdle()
        model.refresh()
        model.refresh() // ignored while one is running
        advanceUntilIdle()
        assertTrue(model.state.value.isRefreshing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.isRefreshing)
        assertEquals(1, refreshes)
    }

    @Test
    fun addTileHidesAtSixPhotos() {
        val six = (0 until 6).map { photo("p$it", it) }
        assertTrue(ProfileUiState(orderedPhotos = six.take(5)).canAddPhoto)
        assertFalse(ProfileUiState(orderedPhotos = six).canAddPhoto)
    }
}
