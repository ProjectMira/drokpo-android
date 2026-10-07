@file:OptIn(ExperimentalCoroutinesApi::class)

package app.drokpo.android.features.profile

import android.net.Uri
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.ProfileUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Routes `viewModelScope` (Dispatchers.Main.immediate) onto a test dispatcher. */
class ProfileMainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

internal fun photo(name: String, order: Int = 0) = Photo(storagePath = "users/me/photos/$name.jpg", order = order)

/** Records every call; [gate] (when set) suspends the next call until completed. */
internal class FakeProfileApi : ProfileApi {
    val calls = mutableListOf<String>()
    val updates = mutableListOf<ProfileUpdate>()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    private suspend fun record(call: String) {
        calls += call
        gate?.await()
        failure?.let { throw it }
    }

    override suspend fun uploadPhoto(uri: Uri): String = error("JVM tests use addPhotoFrom")

    override suspend fun confirmPhoto(storagePath: String, order: Int) = record("confirm $storagePath @$order")

    override suspend fun deletePhoto(storagePath: String) = record("delete $storagePath")

    override suspend fun reorderPhotos(storagePaths: List<String>) = record("reorder ${storagePaths.joinToString(",")}")

    override suspend fun updateProfile(update: ProfileUpdate) {
        updates += update
        record("update")
    }
}
