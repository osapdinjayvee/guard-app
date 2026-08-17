package com.minsu.guardapp.domain.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The span of time a shift occupies.
 *
 * This exists because "today" was being used as a proxy for "this shift", and the two are not the
 * same thing. A guard rostered 23:00–07:00 has their Time In on one date and their Time Out on the
 * next; everything that counts what happened during that shift — has it been closed, which post was
 * it opened at, how much of the round is walked — was asking about the calendar day and so lost the
 * first half of every night shift at midnight.
 */
data class ShiftWindow(val start: Long, val end: Long) {
    operator fun contains(millis: Long): Boolean = millis in start..end

    /**
     * Widened at the ends, because the shift's edges are not the edges of what belongs to it.
     *
     * A guard may clock on a few minutes early — the office sets how many — and that Time In is
     * part of this shift. Without the padding it falls outside the window, the app cannot see it
     * after midnight, and offers Time In a second time: the same bug this class exists to fix,
     * wearing a different hat.
     */
    fun padded(beforeMinutes: Int, afterMinutes: Int = 0): ShiftWindow = ShiftWindow(
        start = start - beforeMinutes * 60_000L,
        end = end + afterMinutes * 60_000L,
    )
}

/**
 * Where this duty sits on the calendar, or null when the office left the hours blank.
 *
 * A shift ending at or before it starts runs through midnight and ends the following day. Read
 * literally it would be a span of negative length containing no instant at all, which is what left
 * every night guard falling through to whatever else the schedule happened to hold. The server
 * decides this the same way, in `ScheduleEntry::endsAt()`, and the two must not drift: the app
 * offers the buttons, the server accepts what they produce.
 */
fun DutyAssignment.shiftWindow(): ShiftWindow? {
    val start = shiftStart() ?: return null
    val end = date.at(endsAt) ?: return null

    return ShiftWindow(start, if (end <= start) end.plusOneDay() else end)
}

/**
 * When the shift begins, independently of whether the office gave it an end.
 *
 * Kept separate because the two are needed separately: an entry with a start and no end cannot be
 * bounded, but it can still say when the guard may first clock on. Folding this into
 * [shiftWindow] silently disabled the too-early rule for every half-filled entry.
 */
fun DutyAssignment.shiftStart(): Long? = date.at(startsAt)

/**
 * The span to count a shift's attendance over. Always answers.
 *
 * The shift's own window where it has one; otherwise the local day containing [nowMillis] — which
 * is what every one of these queries did before, and remains the only sane reading of a duty the
 * office filed without hours. A rest day is filed exactly that way.
 */
fun DutyAssignment?.attendanceWindow(nowMillis: Long): ShiftWindow =
    this?.shiftWindow() ?: localDayOf(nowMillis)

private fun localDayOf(millis: Long): ShiftWindow {
    val start = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    return ShiftWindow(start.timeInMillis, start.plusOneDay().timeInMillis - 1)
}

/** `2026-08-16` plus `23:00:00` or `23:00`, in the guard's own timezone. */
private fun String.at(hhmmss: String?): Long? {
    val parts = hhmmss?.split(':') ?: return null
    val hours = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minutes = parts.getOrNull(1)?.toIntOrNull() ?: return null
    val seconds = parts.getOrNull(2)?.toIntOrNull() ?: 0

    val day = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(this)
    }.getOrNull() ?: return null

    return Calendar.getInstance().apply {
        time = day
        set(Calendar.HOUR_OF_DAY, hours)
        set(Calendar.MINUTE, minutes)
        set(Calendar.SECOND, seconds)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/**
 * A calendar day later, not 86,400,000 milliseconds later.
 *
 * The two differ across a daylight-saving boundary. The Philippines has none, so the arithmetic
 * would survive here — but this is the kind of shortcut that is copied into a codebase that later
 * needs to be right somewhere else.
 */
private fun Long.plusOneDay(): Long = Calendar.getInstance()
    .apply {
        timeInMillis = this@plusOneDay
        add(Calendar.DAY_OF_MONTH, 1)
    }
    .timeInMillis

private fun Calendar.plusOneDay(): Calendar =
    (clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
