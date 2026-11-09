package com.minsu.guardapp.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** A photo brought in from the gallery, and when it says it was taken ? if it says. */
data class GalleryImport(val file: File, val takenAtMillis: Long?)

/**
 * Produces an observation photo with its time and location burned into the pixels.
 *
 * In-memory capture and a [Watermark], exactly as [SelfieCapture] does and for the same reason: an
 * admin reviewing a report sees the image, not its EXIF, and a photo of a broken window is only
 * evidence if it says when and where it was taken.
 */
@Singleton
class ObservationPhotoCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** App-private; a report's photos do not belong in the gallery. */
    private val directory: File
        get() = File(context.filesDir, "observations").apply { mkdirs() }

    suspend fun capture(
        imageCapture: ImageCapture,
        executor: Executor,
        name: String,
        lines: List<String>,
        maxDimension: Int,
        quality: Int,
    ): File {
        val proxy = takePicture(imageCapture, executor)

        return withContext(Dispatchers.Default) {
            val bitmap = try {
                proxy.toUprightBitmap()
            } finally {
                proxy.close()
            }
            burnAndSave(bitmap, lines, name, maxDimension, quality)
        }
    }

    /**
     * Copies a photo the guard picked from their gallery into the report, stamped like any other.
     *
     * Decoded at a reduced size: a gallery photo from a modern phone is 50 MP, and decoding it whole
     * to burn a watermark onto it would run a cheap handset out of memory. [lines] is given the
     * photo's own date, when the file carries one, so the stamp can say both when it was taken and
     * when it was attached ? the two are not the same, and the office should not have to guess.
     *
     * The photo picker removes the location from what it hands over, so none is read here; the caller
     * stamps the guard's position at the moment of attaching and says that is what it is.
     */
    suspend fun importFromGallery(
        uri: Uri,
        name: String,
        lines: (takenAtMillis: Long?) -> List<String>,
        maxDimension: Int,
        quality: Int,
    ): GalleryImport = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val exif = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        val takenAt = exif?.takenAtMillis()
        val rotation = exif?.rotationDegrees() ?: 0

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("That file is not a photo this phone can read.")
        }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2

        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("That photo could not be opened.")

        val upright = if (rotation == 0) {
            decoded
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }

        GalleryImport(
            file = burnAndSave(upright, lines(takenAt), name, maxDimension, quality),
            takenAtMillis = takenAt,
        )
    }

    private fun burnAndSave(
        bitmap: Bitmap,
        lines: List<String>,
        name: String,
        maxDimension: Int,
        quality: Int,
    ): File {
        val burned = Watermark.burn(bitmap, lines, maxDimension)
        if (burned !== bitmap) bitmap.recycle()

        val file = File(directory, "$name.jpg")
        try {
            // The server refuses anything over 5 MB, permanently. At the dimensions settings hand
            // out that never happens, but a server that raises the dimension should cost quality,
            // not a report stuck in Rejected.
            var currentQuality = quality
            do {
                file.outputStream().use { burned.compress(Bitmap.CompressFormat.JPEG, currentQuality, it) }
                currentQuality -= QUALITY_STEP
            } while (file.length() > MAX_UPLOAD_BYTES && currentQuality >= MIN_QUALITY)
        } finally {
            burned.recycle()
        }
        return file
    }

    private suspend fun takePicture(imageCapture: ImageCapture, executor: Executor): ImageProxy =
        suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) = continuation.resume(image)
                    override fun onError(exception: ImageCaptureException) =
                        continuation.resumeWithException(exception)
                },
            )
        }

    private fun ExifInterface.rotationDegrees(): Int =
        when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    /**
     * The camera's own record of when the photo was taken, read as the phone's local time.
     *
     * Null when absent, unreadable, or in the future: a date a file merely claims is worth stamping
     * only if it is at least possible.
     */
    private fun ExifInterface.takenAtMillis(): Long? {
        val raw = getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null

        val parsed = runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { isLenient = false }.parse(raw)?.time
        }.getOrNull() ?: return null

        return parsed.takeIf { it in 1..System.currentTimeMillis() }
    }

    private companion object {
        /** The server's limit: 5120 KB per photo. */
        const val MAX_UPLOAD_BYTES = 5_120L * 1024
        const val QUALITY_STEP = 10
        const val MIN_QUALITY = 40
    }
}
