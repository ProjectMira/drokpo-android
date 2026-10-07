package app.drokpo.android.features.communityhome

import android.net.Uri
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.PhotoUploader
import app.drokpo.android.core.model.CommunityPhotoConfirm
import app.drokpo.android.core.model.CommunityPostIn
import app.drokpo.android.core.model.CommunityUpdate

/**
 * What the community account's own screens (profile editor, post composer,
 * settings) send to the backend. An interface so the models run in plain JVM
 * tests against a fake; [RemoteCommunityAccountApi] is every model's default.
 */
internal interface CommunityAccountApi {
    /** PATCH /api/communities/me. */
    suspend fun updateCommunity(update: CommunityUpdate)

    /** Downscale + upload to communities/{uid}/photos/{uuid}.jpg → the storage path. */
    suspend fun uploadCommunityPhoto(image: Uri): String

    /** POST /api/communities/me/photos {storagePath, order}. */
    suspend fun confirmPhoto(storagePath: String, order: Int)

    /** DELETE /api/communities/me/photos?storage_path=… */
    suspend fun deletePhoto(storagePath: String)

    /** POST /api/communities/me/posts. */
    suspend fun createPost(post: CommunityPostIn)

    /** DELETE /api/communities/me — the backend also deletes the Firebase Auth user. */
    suspend fun deleteCommunity()
}

internal object RemoteCommunityAccountApi : CommunityAccountApi {
    override suspend fun updateCommunity(update: CommunityUpdate) {
        ApiClient.patch<EmptyResponse>("/api/communities/me", update)
    }

    override suspend fun uploadCommunityPhoto(image: Uri): String = PhotoUploader.uploadCommunityPhoto(image)

    override suspend fun confirmPhoto(storagePath: String, order: Int) {
        ApiClient.post<EmptyResponse>("/api/communities/me/photos", CommunityPhotoConfirm(storagePath = storagePath, order = order))
    }

    override suspend fun deletePhoto(storagePath: String) {
        ApiClient.delete<EmptyResponse>("/api/communities/me/photos", listOf("storage_path" to storagePath))
    }

    override suspend fun createPost(post: CommunityPostIn) {
        ApiClient.post<EmptyResponse>("/api/communities/me/posts", post)
    }

    override suspend fun deleteCommunity() {
        ApiClient.delete<EmptyResponse>("/api/communities/me")
    }
}

/**
 * Swift `trimmingCharacters(in: .whitespaces)`: strips spaces and tabs (Unicode
 * Zs + U+0009) from both ends — but not newlines, unlike Kotlin's `trim()`.
 */
internal fun String.trimWhitespaces(): String =
    trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/** iOS `nonEmpty(_:)`: the trimmed text, or null when nothing is left. */
internal fun String.nonEmptyTrimmed(): String? = trimWhitespaces().ifEmpty { null }
