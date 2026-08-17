package com.minsu.guardapp.data

import com.minsu.guardapp.core.database.ScheduleEntity
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.shiftWindow

/**
 * The one shift that matters right now, out of a schedule that may hold several.
 *
 * A day is not one shift. The office splits duties, covers absences, and overrides a rest day into
 * work, so a guard can hold an 06:00–14:00 that was cancelled and a 15:00–23:00 that replaced it,
 * both filed against the same date.
 *
 * And a shift is not a day. A guard rostered 23:00–07:00 is still working at 00:30, on a date very
 * often filed as their day off. This used to compare clock times alone — minutes past midnight,
 * with the entry's own date thrown away — so at midnight the running shift vanished and the next
 * day's entry answered in its place. The guard was told "not on duty" in the middle of their shift,
 * and if they scanned anyway the server saw a Time Out against a rest day and refused it.
 *
 * So it works in absolute time now, via [shiftWindow]. The server resolves the same question the
 * same way in `User::scheduleOn()`, down to the tie-break below, and the two must not drift: this
 * decides which buttons the guard is offered, and that decides whether the result is accepted.
 *
 * A top-level function rather than a private helper so it can be tested for what it is: a decision
 * about time, which needs no database, no network and no repository to exercise.
 *
 * @param today the guard's local date. Entries filed against any other date can only win by
 *   covering [nowMillis] — never as "the next one" or "the last one", or a night shift that ended
 *   at 07:00 would still be the answer at ten the same morning.
 */
internal fun List<ScheduleEntity>.currentOrNext(nowMillis: Long, today: String): DutyAssignment? {
    if (isEmpty()) return null

    val duties = mapNotNull { it.toDomain() }

    // Today's entries are searched first so that the boundary instant — where a night shift's
    // inclusive end meets a morning shift's inclusive start at exactly 07:00 — belongs to the shift
    // beginning rather than the one ending.
    val (todays, others) = duties.partition { it.date == today }

    (todays + others)
        .firstOrNull { duty -> duty.shiftWindow()?.contains(nowMillis) == true }
        ?.let { return it }

    if (todays.isEmpty()) return null

    todays
        .filter { (it.shiftWindow()?.start ?: Long.MIN_VALUE) > nowMillis }
        .minByOrNull { it.shiftWindow()?.start ?: Long.MAX_VALUE }
        ?.let { return it }

    // Entries the office filed without hours cannot be placed in the day, but they are still
    // duties — a rest day is filed in exactly that shape. Falling back to the last one keeps them
    // visible instead of reading as a schedule nobody filled in.
    return todays.last()
}
