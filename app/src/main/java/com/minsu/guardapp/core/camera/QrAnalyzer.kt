package com.minsu.guardapp.core.camera

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes QR codes from the camera stream.
 *
 * Every [ImageProxy] must be closed, on every path, or CameraX stops delivering frames after a
 * handful and the preview freezes with no error. That is why `close()` lives in the completion
 * listener rather than after the `addOnSuccessListener` — and why building the [InputImage] is
 * guarded too: a device whose frame layout ML Kit rejects would otherwise throw out of
 * [analyze], leak the proxy, and stall the scanner dead after a few frames with nothing logged.
 *
 * [onCode] fires at most once: a QR code sits in frame for many frames, and the checkpoint must
 * be resolved a single time.
 */
@OptIn(ExperimentalGetImage::class)
class QrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val delivered = AtomicBoolean(false)

    /** Logged once per analyzer, not per frame: decoding runs at ~30fps and would flood logcat. */
    private val failureLogged = AtomicBoolean(false)

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || delivered.get()) {
            imageProxy.close()
            return
        }

        val input = runCatching {
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        }.getOrElse { error ->
            logOnce("could not wrap a ${mediaImage.format} frame for decoding", error)
            imageProxy.close()
            return
        }

        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { it.rawValue }?.let { value ->
                    // compareAndSet, not a plain flag: analyze() runs on the analyzer executor
                    // and several frames can be in flight when the code first appears.
                    if (delivered.compareAndSet(false, true)) onCode(value)
                }
            }
            // Without this, a scanner that fails on every frame — a model that will not load, a
            // frame format the device serves differently — is indistinguishable from one pointed
            // at a blank wall. Both just sit there saying "line the QR code up".
            .addOnFailureListener { error -> logOnce("barcode decoding failed", error) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun logOnce(message: String, error: Throwable) {
        if (failureLogged.compareAndSet(false, true)) Log.w(TAG, message, error)
    }

    /** Allows another scan after the guard dismisses a result. */
    fun reset() {
        delivered.set(false)
        failureLogged.set(false)
    }

    private companion object {
        const val TAG = "QrAnalyzer"
    }
}
