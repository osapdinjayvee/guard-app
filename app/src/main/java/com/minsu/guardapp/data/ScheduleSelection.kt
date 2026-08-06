package com.minsu.guardapp.data

import com.minsu.guardapp.core.database.ScheduleEntity
import com.minsu.guardapp.domain.model.DutyAssignment
import java.util.Calendar

/**
 * The one shift that matters right now, out of a day that may hold several.
 *
 * A day is not one shift. The office splits duties, covers absences, and overrides a rest day into
 * work, so a guard can hold an 06:00–14:00 that was cancelled and a 15:00–23:00 that replaced it,
 * both filed against the same date.
 *
 * The same rule the server applies when it answers `today`, kept in step deliberately: the shift in
 * progress; failing that the next one to start; failing that the last one to have finished. The
 * middle case is the one a guard notices — at 14:30, between a morning shift that is over and an
 * afternoon one that has not begun, the useful answer is the one they are about to work.
 *
 * A top-level function rather than a private helper so it can be tested for what it is: a decision
 * about time, which needs no database, no network and no repository to exercise.
 */
internal fun List<ScheduleEntity>.currentOrNext(nowMillis: Long): DutyAssignment? {
    if (isEmpty()) return null

    val nowMinutes = Calendar.getInstance()
        .apply { timeInMillis = nowMillis }
        .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    val running = firstOrNull { entry ->
        val start = entry.startsAt.toMinutesPastMidnight() ?: return@firstOrNull false
        val end = entry.endsAt.toMinutesPastMidnight() ?: return@firstOrNull false

        // A shift ending at or before it starts runs through midnight, so "now" is inside it from
        // the start time to the end of the day. Read literally it would be a shift of negative
        // length containing no instant at all, and every night guard would fall through to
        // whatever else the day happened to hold.
        if (end <= start) nowMinutes >= start else nowMinutes in start..end
    }

    val next = firstOrNull { entry ->
        (entry.startsAt.toMinutesPastMidnight() ?: return@firstOrNull false) > nowMinutes
    }

    // Entries the office filed without hours cannot be placed in the day, but they are still
    // duties: falling back to the last one keeps them visible instead of reading as a rest day.
    return (running ?: next ?: last()).toDomain()
}

/** `15:00:00` or `15:00` to minutes past midnight. Null when the office left the hours blank. */
private fun String?.toMinutesPastMidnight(): Int? {
    val parts = this?.split(':') ?: return null
    val hours = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minutes = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return hours * 60 + minutes
}
