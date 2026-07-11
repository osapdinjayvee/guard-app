package com.appetiser.guardapp.feature.reports

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.appetiser.guardapp.domain.model.AttendanceRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Renders an attendance report to a PDF with the platform's [PdfDocument] — no third-party
 * dependency. It is a bare Canvas API, so the header, table, and pagination are drawn by hand.
 *
 * The file is written to cache/reports and shared through a FileProvider; it is transient, not
 * evidence, so it does not belong with the attendance database.
 */
class ReportPdfWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun write(
        guardName: String,
        rangeLabel: String,
        window: ReportWindow,
        records: List<AttendanceRecord>,
        totals: ReportTotals,
        generatedAtMillis: Long,
    ): File {
        val document = PdfDocument()
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val label = Paint().apply { textSize = 10f; color = 0xFF5A6B62.toInt() }
        val body = Paint().apply { textSize = 11f }
        val bold = Paint().apply { textSize = 11f; isFakeBoldText = true }
        val rule = Paint().apply { strokeWidth = 0.5f; color = 0xFFCCCCCC.toInt() }

        var page = document.startPage(pageInfo(1))
        var canvas = page.canvas
        var y = MARGIN + 24f
        var pageNumber = 1

        canvas.drawText("Guard Attendance Report", MARGIN, y, title)
        y += 22f
        canvas.drawText("Guard: $guardName", MARGIN, y, body); y += 15f
        canvas.drawText("Range: $rangeLabel (${date(window.fromMillis)} – ${date(window.toMillis - 1)})", MARGIN, y, body); y += 15f
        canvas.drawText("Generated: ${dateTime(generatedAtMillis)}", MARGIN, y, label); y += 24f

        canvas.drawText("Time In: ${totals.timeIn}     Time Out: ${totals.timeOut}     Total: ${totals.total}", MARGIN, y, bold)
        y += 22f

        // Column headers
        canvas.drawText("Date & time", COL_DATE, y, bold)
        canvas.drawText("Checkpoint", COL_CHECKPOINT, y, bold)
        canvas.drawText("Type", COL_TYPE, y, bold)
        canvas.drawText("Status", COL_STATUS, y, bold)
        y += 6f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rule)
        y += 16f

        for (record in records) {
            if (y > PAGE_HEIGHT - MARGIN) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(pageInfo(pageNumber))
                canvas = page.canvas
                y = MARGIN + 16f
            }
            canvas.drawText(dateTime(record.capturedAt), COL_DATE, y, body)
            canvas.drawText(record.checkpointCode, COL_CHECKPOINT, y, body)
            canvas.drawText(record.type.name.replace('_', ' '), COL_TYPE, y, body)
            canvas.drawText(record.syncState.name, COL_STATUS, y, body)
            y += 16f
        }

        if (records.isEmpty()) {
            canvas.drawText("No attendance recorded in this range.", MARGIN, y, body)
        }

        document.finishPage(page)

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "attendance-report-${generatedAtMillis}.pdf")
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return file
    }

    private fun pageInfo(number: Int) =
        PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), number).create()

    private companion object {
        const val PAGE_WIDTH = 595f  // A4 at 72dpi
        const val PAGE_HEIGHT = 842f
        const val MARGIN = 40f
        const val COL_DATE = MARGIN
        const val COL_CHECKPOINT = 220f
        const val COL_TYPE = 360f
        const val COL_STATUS = 470f
    }
}

private fun date(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))

private fun dateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(millis))
