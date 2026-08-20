package com.minsu.guardapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The span a *named* date's attendance is counted over — what My schedule opens a round on.
 *
 * Reported from the field: rostered 23:00–06:00 on the 19th and 15:00–23:00 on the 20th, the
 * guard opened the 20th and found a Time Out already sitting there at 6am — nine hours before
 * that shift starts. It was the previous night's, claimed by the 20th because the round asked
 * about the calendar day rather than about the shift.
 */
class ShiftWindowOnDateTest {

    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun at(iso: String): Long = format.parse(iso)!!.time

    private fun shift(date: String, startsAt: String?, endsAt: String?) = DutyAssignment(
        date = date,
        dutyType = DutyType.ROVING,
        dutyName = "Roving Guard",
        startsAt = startsAt,
        endsAt = endsAt,
        totalHours = 8f,
    )

    private val nightThenAfternoon = listOf(
        shift("2026-08-19", "23:00", "06:00"),
        shift("2026-08-20", "15:00", "23:00"),
    )

    /** The reported case. The 6am Time Out belongs to the 19th and must not appear on the 20th. */
    @Test
    fun `an afternoon shift does not claim the previous night's time out`() {
        val window = nightThenAfternoon.windowOn("2026-08-20")

        assertEquals(at("2026-08-20 15:00"), window.start)
        assertEquals(at("2026-08-20 23:00"), window.end)
        assertTrue(at("2026-08-20 06:00") !in window)
    }

    /** The other half of the same bug: the night shift keeps its own Time Out. */
    @Test
    fun `a night shift holds both ends of itself`() {
        val window = nightThenAfternoon.windowOn("2026-08-19")

        assertTrue(at("2026-08-19 23:05") in window)
        assertTrue(at("2026-08-20 06:00") in window)
    }

    /**
     * A day the office filed without hours has nothing else to be bounded by.
     *
     * A rest day is filed exactly that way, so this is the ordinary case rather than a defensive
     * one, and it must not silently produce an empty span that hides a record entirely.
     */
    @Test
    fun `a day filed without hours falls back to the calendar day`() {
        val window = listOf(shift("2026-08-21", null, null)).windowOn("2026-08-21")

        assertEquals(at("2026-08-21 00:00"), window.start)
        assertTrue(at("2026-08-21 23:59") in window)
        assertTrue(at("2026-08-22 00:00") !in window)
    }

    /** A date nobody filed anything for at all. Same fallback, reached a different way. */
    @Test
    fun `a date with no entry falls back to the calendar day`() {
        val window = nightThenAfternoon.windowOn("2026-08-25")

        assertEquals(at("2026-08-25 00:00"), window.start)
        assertTrue(at("2026-08-25 12:00") in window)
    }

    /**
     * A day can hold more than one shift, so the span reaches from the first start to the last
     * end. Picking one would drop the other's records off a round that is opened by date.
     */
    @Test
    fun `two shifts on one day are spanned together`() {
        val window = listOf(
            shift("2026-08-22", "06:00", "14:00"),
            shift("2026-08-22", "18:00", "22:00"),
        ).windowOn("2026-08-22")

        assertEquals(at("2026-08-22 06:00"), window.start)
        assertEquals(at("2026-08-22 22:00"), window.end)
    }

    /*
     * The margins the office allows.
     *
     * The bare shift hours are not the hours a shift's records land in. Bounding a round by them
     * alone shows every shift as still open, because a Time Out is almost never on the minute —
     * which is the same complaint as the night-shift one, on an ordinary same-day shift.
     */

    /** A Time Out four minutes late still belongs to the shift it closes. */
    @Test
    fun `a late time out is still inside its own shift`() {
        val window = nightThenAfternoon.attendanceWindowOn(
            date = "2026-08-20",
            earlyMinutes = 15,
            graceMinutes = 120,
        )

        assertTrue(at("2026-08-20 23:04") in window)
    }

    /** And an early Time In, for the same reason at the other end. */
    @Test
    fun `an early time in is still inside its own shift`() {
        val window = nightThenAfternoon.attendanceWindowOn(
            date = "2026-08-20",
            earlyMinutes = 15,
            graceMinutes = 120,
        )

        assertTrue(at("2026-08-20 14:50") in window)
    }

    /** The padding must not undo the fix: 6am is still the previous night's, grace or no grace. */
    @Test
    fun `the grace does not reach back to the previous night`() {
        val window = nightThenAfternoon.attendanceWindowOn(
            date = "2026-08-20",
            earlyMinutes = 15,
            graceMinutes = 120,
        )

        assertTrue(at("2026-08-20 06:00") !in window)
    }

    /**
     * Nor forward into the next shift.
     *
     * A two-hour grace on a shift ending at 06:00 runs to 08:00. With the next shift starting at
     * 07:00, that would put its Time In on the previous day's round — this bug pointing the other
     * way.
     */
    @Test
    fun `the grace stops short of the next shift`() {
        val window = listOf(
            shift("2026-08-19", "23:00", "06:00"),
            shift("2026-08-20", "07:00", "15:00"),
        ).attendanceWindowOn(date = "2026-08-19", earlyMinutes = 15, graceMinutes = 120)

        assertTrue(at("2026-08-20 06:30") in window)
        assertTrue(at("2026-08-20 07:00") !in window)
    }
}
