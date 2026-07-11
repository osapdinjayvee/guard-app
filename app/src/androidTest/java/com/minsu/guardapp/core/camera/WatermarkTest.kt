package com.minsu.guardapp.core.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * Guards the single requirement most easily faked: the metadata must be in the pixels of the
 * saved file, not merely drawn over the live preview.
 *
 * These assertions decode the JPEG back off disk, so an implementation that only overlays the
 * preview fails here.
 */
@RunWith(AndroidJUnit4::class)
class WatermarkTest {

    @get:Rule val tmp = TemporaryFolder()

    private val lines = listOf(
        "Juan Dela Cruz",
        "10 Jul 2026 · 06:02:11",
        "Lat 14.599512  Lng 120.984222",
        "Accuracy ±8 m",
        "Main Gate (GATE-A)",
        "TIME IN",
    )

    private fun solid(width: Int, height: Int, colour: Int = Color.WHITE): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }

    private fun save(bitmap: Bitmap, quality: Int = 80): File {
        val file = tmp.newFile("selfie_${System.nanoTime()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return file
    }

    /** Rows inside the panel must no longer be the original colour. */
    @Test
    fun the_metadata_survives_a_jpeg_round_trip_to_disk() {
        val source = solid(1200, 1600, Color.WHITE)

        val burned = Watermark.burn(source, lines, maxDimension = 1600)
        val file = save(burned)
        val decoded = BitmapFactory.decodeFile(file.absolutePath)

        // Sample a band where the panel sits: dark pixels can only come from the burn-in.
        val bandY = decoded.height - decoded.height / 12
        val darkPixels = (0 until decoded.width step 8).count { x ->
            val pixel = decoded.getPixel(x, bandY)
            Color.red(pixel) < 120 && Color.green(pixel) < 120 && Color.blue(pixel) < 120
        }

        assertTrue("no dark panel found in the saved file — is the watermark overlay-only?", darkPixels > 10)
    }

    @Test
    fun the_untouched_area_of_the_photo_is_preserved() {
        val source = solid(800, 1000, Color.WHITE)

        val burned = Watermark.burn(source, lines, maxDimension = 1600)

        // The top of the frame is the guard's face; it must not be tinted or covered.
        assertEquals(Color.WHITE, burned.getPixel(burned.width / 2, 10))
    }

    @Test
    fun a_photo_larger_than_the_settings_limit_is_downscaled_before_drawing() {
        val source = solid(4000, 3000)

        val burned = Watermark.burn(source, lines, maxDimension = 1600)

        assertEquals("longest edge honours image_max_dimension_px", 1600, burned.width)
        assertEquals(1200, burned.height)
    }

    @Test
    fun a_photo_within_the_limit_keeps_its_size() {
        val source = solid(1000, 750)

        val burned = Watermark.burn(source, lines, maxDimension = 1600)

        assertEquals(1000, burned.width)
        assertEquals(750, burned.height)
    }

    /** An immutable bitmap (as decoded from JPEG bytes) must not throw when drawn on. */
    @Test
    fun an_immutable_source_bitmap_is_copied_rather_than_drawn_on() {
        val bytes = java.io.ByteArrayOutputStream().also {
            solid(600, 800).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        val immutable = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertTrue("precondition: decoded bitmaps are immutable", !immutable.isMutable)

        val burned = Watermark.burn(immutable, lines, maxDimension = 1600)

        assertTrue(burned.isMutable)
    }

    @Test
    fun the_panel_darkens_a_bright_scene_so_white_text_stays_legible() {
        val burned = Watermark.burn(solid(900, 1200, Color.WHITE), lines, maxDimension = 1600)

        val insidePanel = burned.getPixel(4, burned.height - 4)
        assertNotEquals("panel must cover the bottom edge", Color.WHITE, insidePanel)
    }
}
