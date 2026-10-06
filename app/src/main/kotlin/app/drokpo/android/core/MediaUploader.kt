package app.drokpo.android.core

import android.net.Uri
import app.drokpo.android.core.StorageUploads.awaitCancellable
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Uploads voice-comment audio and chat photo/voice media to Firebase
 * Storage. Comment audio returns a storage PATH — the backend resolves the
 * download URL server-side (same convention as a post's photoStoragePath);
 * chat media returns a download URL directly, since chat messages are
 * written straight to Firestore from the client with no backend round-trip.
 *
 * Errors are [PhotoUploaderError]s (iOS's MediaUploaderError has the same
 * two cases and messages). Storage rules: commentAudio < 5 MB `audio/…`,
 * chatMedia < 10 MB `image/…` or `audio/…`.
 */
object MediaUploader {
    /** iOS `metadata.contentType` for every recording (AAC in an MPEG-4 container). */
    private const val AUDIO_CONTENT_TYPE = "audio/m4a"

    /** commentAudio/{uid}/{uuid}.m4a → storage path. */
    suspend fun uploadCommentAudio(file: File): String {
        val uid = StorageUploads.requireUid()
        val path = "commentAudio/$uid/${StorageUploads.newFileId()}.m4a"
        uploadAudioFile(file, StorageUploads.ref(path))
        return path
    }

    /** chatMedia/{uid}/{uuid}.jpg (downscaled like PhotoUploader) → download URL. */
    suspend fun uploadChatPhoto(uri: Uri): String {
        val uid = StorageUploads.requireUid()
        val data = PhotoUploader.downscaledJpeg(uri)
        val ref = StorageUploads.ref("chatMedia/$uid/${StorageUploads.newFileId()}.jpg")
        StorageUploads.putJpeg(ref, data)
        return ref.downloadUrl.await().toString()
    }

    /** chatMedia/{uid}/{uuid}.m4a → download URL. */
    suspend fun uploadChatAudio(file: File): String {
        val uid = StorageUploads.requireUid()
        val ref = StorageUploads.ref("chatMedia/$uid/${StorageUploads.newFileId()}.m4a")
        uploadAudioFile(file, ref)
        return ref.downloadUrl.await().toString()
    }

    private suspend fun uploadAudioFile(file: File, ref: StorageReference) {
        val metadata = StorageMetadata.Builder().setContentType(AUDIO_CONTENT_TYPE).build()
        ref.putFile(Uri.fromFile(file), metadata).awaitCancellable()
    }
}
