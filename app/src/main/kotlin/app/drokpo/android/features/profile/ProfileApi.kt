package app.drokpo.android.features.profile

import android.net.Uri
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.PhotoUploader
import app.drokpo.android.core.model.PhotoConfirm
import app.drokpo.android.core.model.PhotoOrderUpdate
import app.drokpo.android.core.model.ProfileUpdate

/**
 * The person-profile endpoints ProfileScreen and EditProfileScreen call
 * (backend `routers/profile.py`). An interface so the ViewModels can be
 * driven by fakes in JVM tests; [Live] is the production implementation.
 */
internal interface ProfileApi {
    /** PhotoUploader.upload → `users/{uid}/photos/{uuid}.jpg`; returns the storage path. */
    suspend fun uploadPhoto(uri: Uri): String

    /** POST /api/profile/me/photos {storagePath, order}. */
    suspend fun confirmPhoto(storagePath: String, order: Int)

    /** DELETE /api/profile/me/photos?storage_path=… */
    suspend fun deletePhoto(storagePath: String)

    /** PATCH /api/profile/me/photos/order {storagePaths}. */
    suspend fun reorderPhotos(storagePaths: List<String>)

    /** PATCH /api/profile/me with a (partial) ProfileUpdate — omitted fields stay unchanged. */
    suspend fun updateProfile(update: ProfileUpdate)

    object Live : ProfileApi {
        override suspend fun uploadPhoto(uri: Uri): String = PhotoUploader.upload(uri)

        override suspend fun confirmPhoto(storagePath: String, order: Int) {
            ApiClient.post<EmptyResponse>("/api/profile/me/photos", PhotoConfirm(storagePath = storagePath, order = order))
        }

        override suspend fun deletePhoto(storagePath: String) {
            ApiClient.delete<EmptyResponse>("/api/profile/me/photos", listOf("storage_path" to storagePath))
        }

        override suspend fun reorderPhotos(storagePaths: List<String>) {
            ApiClient.patch<EmptyResponse>("/api/profile/me/photos/order", PhotoOrderUpdate(storagePaths = storagePaths))
        }

        override suspend fun updateProfile(update: ProfileUpdate) {
            ApiClient.patch<EmptyResponse>("/api/profile/me", update)
        }
    }
}
