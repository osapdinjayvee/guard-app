package com.minsu.guardapp.core.camera

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
 * listener rather than after the `addOnSuccessListener`.
 *
 * [onCode] fires at most once: a QR code sits in frame for many frames, and the checkpoint must
 * be resolved a single time.
 */
@OptIn(ExperimentalGetImage::class)
class QrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val delivered = AtomicBoolean(false)

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

        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { it.rawValue }?.let { value ->
                    // compareAndSet, not a plain flag: analyze() runs on the analyzer executor
                    // and several frames can be in flight when the code first appears.
                    if (delivered.compareAndSet(false, true)) onCode(value)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    /** Allows another scan after the guard dismisses a result. */
    fun reset() = delivered.set(false)
}
