package com.appetiser.guardapp.core.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.VisibleForTesting
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the attendance metadata into the pixels of the captured selfie.
 *
 * EXIF is not sufficient. It is invisible, it is stripped by any re-encode, and an admin
 * reviewing an attendance record sees the image, not its tags. The PRD (§7.5) calls for a
 * permanent watermark, so the text is rasterised into the bitmap that gets saved.
 */
object Watermark {

    /**
     * Scales [source] so its longest edge is at most [maxDimension], then draws [lines] over a
     * translucent panel at the bottom.
     *
     * Downscaling happens *before* drawing: a full-resolution bitmap from a modern sensor is
     * tens of megabytes, and burning text onto it is a reliable way to OOM a cheap phone.
     * [maxDimension] comes from `GET /api/settings`, never from a constant here.
     */
    fun burn(source: Bitmap, lines: List<String>, maxDimension: Int): Bitmap {
        val scaled = downscale(source, maxDimension)
        // The source may be immutable (decoded from JPEG bytes), so draw on a mutable copy.
        val target = scaled.copy(Bitmap.Config.ARGB_8888, /* isMutable = */ true)
        if (scaled !== source) scaled.recycle()

        val canvas = Canvas(target)
        val shortEdge = min(target.width, target.height)

        val textSize = shortEdge * TEXT_SIZE_RATIO
        val padding = shortEdge * PADDING_RATIO
        val lineGap = textSize * LINE_GAP_RATIO

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val panelPaint = Paint().apply { color = PANEL_COLOUR }

        val panelHeight = padding * 2 + lines.size * lineGap - (lineGap - textSize)
        canvas.drawRect(
            0f,
            target.height - panelHeight,
            target.width.toFloat(),
            target.height.toFloat(),
            panelPaint,
        )

        var baseline = target.height - panelHeight + padding + textSize
        lines.forEach { line ->
            canvas.drawText(line, padding, baseline, textPaint)
            baseline += lineGap
        }

        return target
    }

    @VisibleForTesting
    fun downscale(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxDimension) return source

        val ratio = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt(),
            (source.height * ratio).toInt(),
            /* filter = */ true,
        )
    }

    /** Dark enough that white text stays legible over a bright sky or a white wall. */
    private const val PANEL_COLOUR = 0xB3000000.toInt()
    private const val TEXT_SIZE_RATIO = 0.035f
    private const val PADDING_RATIO = 0.030f
    private const val LINE_GAP_RATIO = 1.35f
}
