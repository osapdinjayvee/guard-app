package com.minsu.guardapp.core.camera

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Captures an observation photo with its time and location burned into the pixels.
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
            file
        }
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

    private companion object {
        /** The server's limit: 5120 KB per photo. */
        const val MAX_UPLOAD_BYTES = 5_120L * 1024
        const val QUALITY_STEP = 10
        const val MIN_QUALITY = 40
    }
}
