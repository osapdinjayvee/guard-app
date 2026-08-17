package com.minsu.guardapp.data

import com.minsu.guardapp.core.database.ScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Which shift the app is working, when the schedule holds more than one.
 *
 * Two separate reports from the field are pinned here.
 *
 * A guard's morning shift was cancelled and an afternoon one put in its place; the phone went on
 * showing the cancelled morning, because the cache was keyed on the date and only one entry per day
 * survived. Keeping both was half the fix; choosing between them is this.
 *
 * And a night guard rostered 23:00–07:00 was told "not on duty" at half past midnight, in the
 * middle of their own shift, because this compared clock times with the entry's date thrown away.
 * Every test below states its date explicitly for that reason: the date is now load-bearing.
 */
class ScheduleSelectionTest {

    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** An instant, written the way a guard would read it off their phone. */
    private fun at(iso: String): Long = format.parse(iso)!!.time

    private fun shift(
        id: Long,
        startsAt: String?,
        endsAt: String?,
        date: String = TODAY,
        dutyType: String = "SG",
    ) = ScheduleEntity(
        id = id,
        date = date,
        dutyType = dutyType,
        dutyName = if (dutyType == "OFF") "Day Off" else "Stationed Guard",
        startsAt = startsAt,
        endsAt = endsAt,
        totalHours = 8f,
        updatedAt = 0,
    )

    /** The reported split day: a cancelled morning and the afternoon that replaced it. */
    private val splitDay
        get() = listOf(
            shift(1, "06:00:00", "14:00:00"),
            shift(2, "15:00:00", "23:00:00"),
        )

    @Test
    fun `the shift in progress is the one reported`() {
        assertEquals("15:00:00", splitDay.currentOrNext(at("2026-08-17 15:30"), TODAY)?.startsAt)
    }

    @Test
    fun `the morning shift is reported while it is still running`() {
        assertEquals("06:00:00", splitDay.currentOrNext(at("2026-08-17 07:00"), TODAY)?.startsAt)
    }

    /** 14:30 — the morning is over, the afternoon has not begun. The useful answer is what's next. */
    @Test
    fun `between two shifts the one about to start is reported`() {
        assertEquals("15:00:00", splitDay.currentOrNext(at("2026-08-17 14:30"), TODAY)?.startsAt)
    }

    /** After everything has finished, the day's last shift is still the day's shift. */
    @Test
    fun `after the last shift ends it is still the one reported`() {
        assertEquals("15:00:00", splitDay.currentOrNext(at("2026-08-17 23:45"), TODAY)?.startsAt)
    }

    /** Before anything starts, the day's first shift is the one coming. */
    @Test
    fun `before the first shift starts it is the one reported`() {
        assertEquals("06:00:00", splitDay.currentOrNext(at("2026-08-17 05:00"), TODAY)?.startsAt)
    }

    // --- Shifts that run past midnight ---

    /**
     * The report this was written for.
     *
     * A guard rostered 23:00–07:00 on the 16th, opening the app at 00:30 on the 17th. The 17th is
     * their day off — as it usually is for a night guard — and the old rule compared clock times
     * with the date discarded, so at 00:30 the night shift matched nothing and the day off answered
     * instead. Home said "not on duty" and the scanner offered nothing, mid-shift.
     */
    @Test
    fun `a shift that began yesterday is the one being worked after midnight`() {
        val duty = nightThenRest.currentOrNext(at("2026-08-17 00:30"), TODAY)

        assertEquals(YESTERDAY, duty?.date)
        assertEquals("23:00:00", duty?.startsAt)
    }

    @Test
    fun `the night shift is still the answer one minute before it ends`() {
        assertEquals(YESTERDAY, nightThenRest.currentOrNext(at("2026-08-17 06:59"), TODAY)?.date)
    }

    /** With no grace configured, it stops being the answer the moment it is over. */
    @Test
    fun `once the night shift ends today answers instead`() {
        assertEquals(TODAY, nightThenRest.currentOrNext(at("2026-08-17 07:01"), TODAY)?.date)
    }

    // --- The walk back to the guard house ---

    /**
     * A guard does not stop working at the instant the roster says.
     *
     * They hand over, wait for their relief, walk in from the far end of the campus. A Time Out at
     * 07:20 on a shift that ended at 07:00 is closing *that* shift — and without the grace it
     * resolved to the day off instead and the record was refused. The midnight fix alone just moved
     * the same failure twenty minutes later.
     */
    @Test
    fun `a shift just ended is still the one being closed`() {
        val duty = nightThenRest.currentOrNext(at("2026-08-17 07:20"), TODAY, closeGraceMinutes = 120)

        assertEquals(YESTERDAY, duty?.date)
    }

    /** The grace is not indefinite. Past it, the day off is the honest answer. */
    @Test
    fun `the grace runs out`() {
        val duty = nightThenRest.currentOrNext(at("2026-08-17 11:00"), TODAY, closeGraceMinutes = 120)

        assertEquals(TODAY, duty?.date)
    }

    /**
     * A shift about to start also beats one still inside its grace.
     *
     * At 14:30 on a split day the morning ended at 14:00 and the afternoon starts at 15:00. The
     * useful answer is the shift they are about to work — a rule this app already had, which adding
     * the grace quietly overturned until the order was fixed.
     */
    @Test
    fun `the shift about to start wins over one still in its grace`() {
        val duty = splitDay.currentOrNext(at("2026-08-17 14:30"), TODAY, closeGraceMinutes = 120)

        assertEquals("15:00:00", duty?.startsAt)
    }

    /**
     * A shift that has genuinely started beats one still inside its grace.
     *
     * At 07:10 the night shift's grace is running and the morning shift has begun. Handing back the
     * one that just ended would judge the new shift's Time In against last night's schedule.
     */
    @Test
    fun `a shift that has started wins over one still in its grace`() {
        val handover = listOf(
            shift(1, "23:00:00", "07:00:00", date = YESTERDAY, dutyType = "RG"),
            shift(2, "07:00:00", "15:00:00", date = TODAY),
        )

        val duty = handover.currentOrNext(at("2026-08-17 07:10"), TODAY, closeGraceMinutes = 120)

        assertEquals(TODAY, duty?.date)
    }

    /**
     * Looking back at yesterday must not become yesterday answering forever.
     *
     * A finished shift can only ever win by covering the moment asked about. At ten the next
     * morning the guard is on their day off, and the day off is the honest answer.
     */
    @Test
    fun `yesterdays finished shift does not answer for today`() {
        val yesterdaysDay = listOf(
            shift(1, "06:00:00", "14:00:00", date = YESTERDAY),
            shift(2, null, null, date = TODAY, dutyType = "OFF"),
        )

        assertEquals(TODAY, yesterdaysDay.currentOrNext(at("2026-08-17 10:00"), TODAY)?.date)
    }

    /** A night shift ending at 07:00 and a day shift starting at 07:00 both contain that instant. */
    @Test
    fun `at the boundary instant the shift beginning wins over the one ending`() {
        val handover = listOf(
            shift(1, "23:00:00", "07:00:00", date = YESTERDAY, dutyType = "RG"),
            shift(2, "07:00:00", "15:00:00", date = TODAY),
        )

        assertEquals(TODAY, handover.currentOrNext(at("2026-08-17 07:00"), TODAY)?.date)
    }

    /** Nothing filed for today, and yesterday's shift long over. */
    @Test
    fun `yesterday alone reports nothing once its shift has ended`() {
        val onlyYesterday = listOf(shift(1, "06:00:00", "14:00:00", date = YESTERDAY))

        assertNull(onlyYesterday.currentOrNext(at("2026-08-17 10:00"), TODAY))
    }

    @Test
    fun `a shift running through midnight is recognised before midnight too`() {
        val nights = listOf(shift(1, "22:00:00", "06:00:00"))

        assertEquals("22:00:00", nights.currentOrNext(at("2026-08-17 23:30"), TODAY)?.startsAt)
    }

    // --- Rest days and half-filled weeks ---

    @Test
    fun `an empty schedule reports nothing`() {
        assertNull(emptyList<ScheduleEntity>().currentOrNext(at("2026-08-17 10:00"), TODAY))
    }

    /**
     * A rest day is a duty, not the absence of one.
     *
     * The office files these deliberately. Reporting null would make a rest day indistinguishable
     * from a week nobody filled in, and the guard could not tell which they were looking at.
     */
    @Test
    fun `a rest day is reported as a duty`() {
        val restDay = listOf(shift(1, null, null, dutyType = "OFF"))

        assertEquals("Day Off", restDay.currentOrNext(at("2026-08-17 10:00"), TODAY)?.dutyName)
    }

    /** Hours the office left blank must not drop the duty — it is still a duty. */
    @Test
    fun `a duty with no hours is still reported`() {
        val hoursMissing = listOf(shift(1, null, null))

        assertEquals("Stationed Guard", hoursMissing.currentOrNext(at("2026-08-17 10:00"), TODAY)?.dutyName)
    }

    /** A single shift is the answer whatever the hour, and must not be lost to the split-day rules. */
    @Test
    fun `an ordinary single-shift day is unaffected`() {
        val ordinary = listOf(shift(1, "08:00:00", "17:00:00"))

        assertEquals("08:00:00", ordinary.currentOrNext(at("2026-08-17 03:00"), TODAY)?.startsAt)
        assertEquals("08:00:00", ordinary.currentOrNext(at("2026-08-17 12:00"), TODAY)?.startsAt)
        assertEquals("08:00:00", ordinary.currentOrNext(at("2026-08-17 21:00"), TODAY)?.startsAt)
    }

    private val nightThenRest
        get() = listOf(
            shift(1, "23:00:00", "07:00:00", date = YESTERDAY, dutyType = "RG"),
            shift(2, null, null, date = TODAY, dutyType = "OFF"),
        )

    private companion object {
        const val YESTERDAY = "2026-08-16"
        const val TODAY = "2026-08-17"
    }
}
