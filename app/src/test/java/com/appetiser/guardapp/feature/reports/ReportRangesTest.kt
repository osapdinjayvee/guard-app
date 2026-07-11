package com.appetiser.guardapp.feature.reports

import com.appetiser.guardapp.domain.model.AttendanceRecord
import com.appetiser.guardapp.domain.model.AttendanceType
import com.appetiser.guardapp.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReportRangesTest {

    private val manila = TimeZone.getTimeZone("Asia/Manila") // UTC+8, no DST

    private fun instant(year: Int, month: Int, day: Int, hour: Int, min: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, min, 0)
        }.timeInMillis

    @Test
    fun `daily is local midnight to midnight`() {
        val now = instant(2026, 7, 10, 14, 30, manila)

        val window = ReportRanges.windowFor(ReportRange.DAILY, now, manila)

        assertEquals(instant(2026, 7, 10, 0, 0, manila), window.fromMillis)
        assertEquals(instant(2026, 7, 11, 0, 0, manila), window.toMillis)
    }

    /**
     * The reason this class is injectable and tested: an entry filed at 01:00 in UTC+8 must land
     * in *today's* daily report. Computed in UTC it would fall in the previous day.
     */
    @Test
    fun `an early-morning local entry belongs to today, not yesterday`() {
        val now = instant(2026, 7, 10, 1, 0, manila)

        val window = ReportRanges.windowFor(ReportRange.DAILY, now, manila)

        val entryAt0100 = instant(2026, 7, 10, 1, 0, manila)
        assertTrue(entryAt0100 >= window.fromMillis && entryAt0100 < window.toMillis)
    }

    @Test
    fun `monthly spans the whole calendar month`() {
        val now = instant(2026, 7, 15, 9, 0, manila)

        val window = ReportRanges.windowFor(ReportRange.MONTHLY, now, manila)

        assertEquals(instant(2026, 7, 1, 0, 0, manila), window.fromMillis)
        assertEquals(instant(2026, 8, 1, 0, 0, manila), window.toMillis)
    }

    @Test
    fun `weekly is exactly seven days`() {
        val now = instant(2026, 7, 10, 12, 0, manila)

        val window = ReportRanges.windowFor(ReportRange.WEEKLY, now, manila)

        assertEquals(7 * 24 * 60 * 60 * 1000L, window.toMillis - window.fromMillis)
        assertTrue("now falls inside its own week", now >= window.fromMillis && now < window.toMillis)
    }

    @Test
    fun `custom extends the to-day to its end so that day is included`() {
        val from = instant(2026, 7, 1, 0, 0, manila)
        val toDay = instant(2026, 7, 3, 15, 0, manila) // an afternoon on the 3rd

        val window = ReportRanges.windowFor(ReportRange.CUSTOM, from, manila, customFrom = from, customTo = toDay)

        assertEquals(from, window.fromMillis)
        // The whole of the 3rd is covered: end is midnight starting the 4th.
        assertEquals(instant(2026, 7, 4, 0, 0, manila), window.toMillis)
    }

    @Test
    fun `totals count each attendance type`() {
        val records = listOf(
            record("a", AttendanceType.TIME_IN),
            record("b", AttendanceType.TIME_OUT),
            record("c", AttendanceType.TIME_IN),
        )

        val totals = ReportRanges.totalsOf(records)

        assertEquals(2, totals.timeIn)
        assertEquals(1, totals.timeOut)
        assertEquals(3, totals.total)
    }

    private fun record(id: String, type: AttendanceType) = AttendanceRecord(
        id = id, checkpointCode = "GATE-A", type = type, capturedAt = 0, selfiePath = "/x.jpg",
        latitude = null, longitude = null, accuracyMetres = null,
        syncState = SyncState.SYNCED, lastError = null,
    )
}
