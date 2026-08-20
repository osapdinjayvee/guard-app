package com.minsu.guardapp.core.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * A PDF on disk, rendered a page at a time.
 *
 * Extracted from the handbook because the app now shows three documents — the handbook, the DTR
 * and the guard report — and every one of them needs the same three unobvious things right:
 * one page open at a time, a white background under the ink, and the file descriptor closed.
 */
class PdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    /**
     * PdfRenderer opens one page at a time and is not safe to use from two places at once.
     *
     * A list renders pages as they scroll into view, which is several coroutines asking at the same
     * moment. Without this they trample each other's open page and the renderer throws.
     */
    private val lock = Mutex()

    /** One page, drawn at [widthPx]. Null when the page cannot be rendered. */
    suspend fun page(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                renderer.openPage(index).use { page ->
                    val height = (widthPx.toFloat() / page.width * page.height)
                        .toInt()
                        .coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    // PdfRenderer draws only ink; the paper is whatever was already in the bitmap,
                    // which is transparent. Left alone the text lands on a black page in dark mode.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }.getOrNull()
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /**
         * Opens [file], or null when it is not a PDF this phone can read.
         *
         * The caller is expected to delete a file that will not open: a stored one that fails here
         * fails identically every time the screen is opened, and deleting it is what lets the next
         * attempt fetch a good copy.
         */
        suspend fun open(file: File): PdfDocument? = withContext(Dispatchers.IO) {
            runCatching {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                runCatching { PdfDocument(PdfRenderer(fd), fd) }
                    .getOrElse {
                        fd.close()
                        throw it
                    }
            }.getOrNull()
        }
    }
}
