package app.drokpo.android.core

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Port of the iOS `PhotoUploaderError` (MediaUploader reuses the same messages). */
sealed class PhotoUploaderError(message: String) : Exception(message) {
    class NotAuthenticated : PhotoUploaderError("You need to sign in again.")
    class InvalidImage : PhotoUploaderError("That photo couldn't be processed. Try a different one.")
}

/**
 * Profile / community photo uploads to Firebase Storage. Returns the storage
 * PATH, which the backend confirm endpoints take (it resolves URLs itself).
 *
 * The storage rules cap photos at 10 MB and require an `image/…` content
 * type; downscaling to [MAX_DIMENSION_PX] keeps every upload far below that.
 * (The optimize_photo Cloud Function later re-encodes in place for viewers.)
 */
object PhotoUploader {
    /** Longest side, in PIXELS (iOS `maxDimension`). */
    const val MAX_DIMENSION_PX = 1600

    /** iOS `jpegQuality` 0.8. */
    const val JPEG_QUALITY = 80

    /** users/{uid}/photos/{uuid}.jpg → storage path. */
    suspend fun upload(uri: Uri): String {
        val uid = StorageUploads.requireUid()
        return upload(uri, "users/$uid/photos/${StorageUploads.newFileId()}.jpg")
    }

    /**
     * Same as [upload] but under a community's own Storage prefix
     * (`communities/{uid}/photos/…`, enforced by storage.rules).
     */
    suspend fun uploadCommunityPhoto(uri: Uri): String {
        val uid = StorageUploads.requireUid()
        return upload(uri, "communities/$uid/photos/${StorageUploads.newFileId()}.jpg")
    }

    private suspend fun upload(uri: Uri, path: String): String {
        val data = downscaledJpeg(uri)
        StorageUploads.putJpeg(StorageUploads.ref(path), data)
        return path
    }

    suspend fun downloadUrl(storagePath: String): String =
        StorageUploads.ref(storagePath).downloadUrl.await().toString()

    /**
     * Decodes a picked photo (content Uri from the system photo picker),
     * applies its EXIF orientation, shrinks it so the longest side is at most
     * [MAX_DIMENSION_PX] pixels — never upscaling — and encodes JPEG at
     * [JPEG_QUALITY]. Shared with MediaUploader (chat photo messages).
     *
     * iOS's comment on why it works in pixels, not points: a screen-scale
     * renderer would upscale a 1600pt canvas to 4800px and blow past the
     * 10MB storage rule limit. Android bitmaps are always pixels.
     *
     * @throws PhotoUploaderError.InvalidImage when the image can't be read or encoded.
     */
    suspend fun downscaledJpeg(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = AppGraph.app.contentResolver
        val bitmap = try {
            decodeOriented(resolver, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PhotoUploaderError.InvalidImage()
        } catch (e: OutOfMemoryError) {
            throw PhotoUploaderError.InvalidImage()
        }
        try {
            encodeJpeg(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    // region Decoding

    private fun decodeOriented(resolver: ContentResolver, uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation itself, reports the
            // oriented size, and samples + scales in one pass.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                // Software pixels: hardware bitmaps can't be drawn into a Canvas.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                targetSize(info.size.width, info.size.height, MAX_DIMENSION_PX)?.let { (w, h) ->
                    decoder.setTargetSize(w, h)
                }
            }
        } else {
            decodeWithExif(resolver, uri)
        }

    /**
     * API 26–27: BitmapFactory ignores EXIF, so read the orientation with
     * androidx.exifinterface and bake it into the pixels. A power-of-two
     * pre-sample keeps a 50 MP camera photo from exhausting memory before the
     * exact resize.
     */
    private fun decodeWithExif(resolver: ContentResolver, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw PhotoUploaderError.InvalidImage()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoUploaderError.InvalidImage()

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION_PX)
        }
        val sampled = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw PhotoUploaderError.InvalidImage()

        val matrix = orientationMatrix(orientation)
        // Rotation never changes the longest side, so the scale works on the
        // un-rotated sample.
        targetSize(sampled.width, sampled.height, MAX_DIMENSION_PX)?.let { (w, _) ->
            val scale = w.toFloat() / sampled.width
            matrix.postScale(scale, scale)
        }
        if (matrix.isIdentity) return sampled
        val transformed = Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
        if (transformed !== sampled) sampled.recycle()
        return transformed
    }

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> Unit
        }
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        // JPEG has no alpha; Android would write transparent pixels as black.
        // Flatten onto white first (screenshots/PNGs with transparency).
        val opaque = if (bitmap.hasAlpha()) {
            createBitmap(bitmap.width, bitmap.height).also { flat ->
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
            }
        } else {
            bitmap
        }
        try {
            val out = ByteArrayOutputStream()
            if (!opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw PhotoUploaderError.InvalidImage()
            }
            return out.toByteArray().takeIf { it.isNotEmpty() } ?: throw PhotoUploaderError.InvalidImage()
        } finally {
            if (opaque !== bitmap) opaque.recycle()
        }
    }

    // endregion

    // region Pure sizing math (unit-tested)

    /**
     * Output size for a [width]×[height] PIXEL image: null when the longest
     * side already fits (iOS: `guard largestSide > maxDimension else { return
     * image.jpegData(…) }` — never upscale); otherwise both sides scaled by
     * `maxDimension / largestSide`, so the longest side is exactly
     * [maxDimension].
     */
    internal fun targetSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int>? {
        require(width > 0 && height > 0) { "Image has no pixels" }
        val largest = max(width, height)
        if (largest <= maxDimension) return null
        val scale = maxDimension.toDouble() / largest
        val w = if (width == largest) maxDimension else (width * scale).roundToInt().coerceAtLeast(1)
        val h = if (height == largest) maxDimension else (height * scale).roundToInt().coerceAtLeast(1)
        return w to h
    }

    /**
     * Largest power-of-two BitmapFactory `inSampleSize` whose sampled image
     * still has a longest side ≥ [maxDimension] (the exact resize then only
     * ever shrinks). 1 for images that already fit.
     */
    internal fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        val largest = max(width, height)
        var sample = 1
        while (largest / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    // endregion
}

/** Firebase Storage plumbing shared by PhotoUploader and MediaUploader. */
internal object StorageUploads {
    fun requireUid(): String {
        val uid = try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (e: IllegalStateException) {
            null // Firebase not configured.
        }
        return uid ?: throw PhotoUploaderError.NotAuthenticated()
    }

    /** iOS `UUID().uuidString` — uppercase, so file names look the same from both apps. */
    fun newFileId(): String = UUID.randomUUID().toString().uppercase()

    fun ref(path: String): StorageReference = FirebaseStorage.getInstance().reference.child(path)

    suspend fun putJpeg(ref: StorageReference, data: ByteArray) {
        val metadata = StorageMetadata.Builder().setContentType("image/jpeg").build()
        ref.putBytes(data, metadata).awaitCancellable()
    }

    /** `Task.await()` doesn't cancel the upload; do it when the caller goes away. */
    suspend fun UploadTask.awaitCancellable(): UploadTask.TaskSnapshot =
        try {
            await()
        } catch (e: CancellationException) {
            cancel()
            throw e
        }
}
