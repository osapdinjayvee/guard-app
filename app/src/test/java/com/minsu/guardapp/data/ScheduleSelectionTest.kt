package com.minsu.guardapp.data

import com.minsu.guardapp.core.database.ScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * Which shift the app calls "today" when the day holds more than one.
 *
 * From the field: a guard's morning shift was cancelled and an afternoon one put in its place. The
 * phone went on showing the cancelled morning, and signing out, back in and syncing changed
 * nothing — the roster cache was keyed on the date, so the second entry overwrote the first on the
 * way in and only ever one survived. Keeping both is half the fix; choosing between them is this.
 */
class ScheduleSelectionTest {

    private fun todayAt(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun shift(id: Long, startsAt: String?, endsAt: String?) = ScheduleEntity(
        id = id,
        date = "2026-08-06",
        dutyType = "SG",
        dutyName = "Stationed Guard",
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
        assertEquals("15:00:00", splitDay.currentOrNext(todayAt(15, 30))?.startsAt)
    }

    @Test
    fun `the morning shift is reported while it is still running`() {
        assertEquals("06:00:00", splitDay.currentOrNext(todayAt(7))?.startsAt)
    }

    /** 14:30 — the morning is over, the afternoon has not begun. The useful answer is what's next. */
    @Test
    fun `between two shifts the one about to start is reported`() {
        assertEquals("15:00:00", splitDay.currentOrNext(todayAt(14, 30))?.startsAt)
    }

    /** After everything has finished, the day's last shift is still the day's shift. */
    @Test
    fun `after the last shift ends it is still the one reported`() {
        assertEquals("15:00:00", splitDay.currentOrNext(todayAt(23, 45))?.startsAt)
    }

    /** Before anything starts, the day's first shift is the one coming. */
    @Test
    fun `before the first shift starts it is the one reported`() {
        assertEquals("06:00:00", splitDay.currentOrNext(todayAt(5))?.startsAt)
    }

    /**
     * A night shift runs past midnight, so its end time is numerically before its start.
     *
     * Read literally that is a shift of negative length containing no instant at all, and a guard
     * on nights would fall through to whatever else the day held.
     */
    @Test
    fun `a shift running through midnight is recognised as in progress`() {
        val nights = listOf(shift(1, "22:00:00", "06:00:00"))

        assertEquals("22:00:00", nights.currentOrNext(todayAt(23, 30))?.startsAt)
    }

    @Test
    fun `a rest day reports nothing`() {
        assertNull(emptyList<ScheduleEntity>().currentOrNext(todayAt(10)))
    }

    /** Hours the office left blank must not drop the duty — it is still a duty. */
    @Test
    fun `a duty with no hours is still reported`() {
        val hoursMissing = listOf(shift(1, null, null))

        assertEquals("Stationed Guard", hoursMissing.currentOrNext(todayAt(10))?.dutyName)
    }

    /** A single shift is the answer whatever the hour, and must not be lost to the split-day rules. */
    @Test
    fun `an ordinary single-shift day is unaffected`() {
        val ordinary = listOf(shift(1, "08:00:00", "17:00:00"))

        assertEquals("08:00:00", ordinary.currentOrNext(todayAt(3))?.startsAt)
        assertEquals("08:00:00", ordinary.currentOrNext(todayAt(12))?.startsAt)
        assertEquals("08:00:00", ordinary.currentOrNext(todayAt(21))?.startsAt)
    }
}
