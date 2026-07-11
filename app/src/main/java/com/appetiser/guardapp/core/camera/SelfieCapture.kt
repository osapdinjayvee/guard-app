package com.appetiser.guardapp.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Captures the attendance selfie with its metadata burned into the saved pixels.
 *
 * Capture is in-memory (`OnImageCapturedCallback`), never `takePicture(OutputFileOptions, …)`.
 * The file variant writes straight to disk and offers no hook to draw on the bitmap first,
 * which would leave the watermark as a preview overlay only — the exact failure the PRD warns
 * against.
 */
@Singleton
class SelfieCapture @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** App-private. The selfie is attendance evidence and does not belong in the gallery. */
    private val directory: File
        get() = File(context.filesDir, "attendance").apply { mkdirs() }

    fun fileFor(id: String) = File(directory, "$id.jpg")

    suspend fun capture(
        imageCapture: ImageCapture,
        executor: Executor,
        id: String,
        lines: List<String>,
        maxDimension: Int,
        quality: Int,
    ): File {
        val proxy = takePicture(imageCapture, executor)
        val bitmap = try {
            proxy.toUprightBitmap()
        } finally {
            proxy.close()
        }

        val burned = Watermark.burn(bitmap, lines, maxDimension)
        if (burned !== bitmap) bitmap.recycle()

        val file = fileFor(id)
        file.outputStream().use { burned.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        burned.recycle()
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
}

/**
 * Applies the sensor rotation so the saved image is upright.
 *
 * The front camera is **not** mirrored here. PreviewView mirrors the live preview so the guard
 * sees themselves as in a mirror, but mirroring the saved evidence would also reverse the
 * burned-in text and any lettering in the scene.
 */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return decoded

    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated !== decoded) decoded.recycle()
    return rotated
}
