package com.minsu.guardapp.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A day on the guard's duty roster, cached.
 *
 * Cached for the same reason the checkpoints are: the scanner has to know whether this guard is
 * stationed or roving *before* it can offer them the right buttons, and it has to know it in a
 * basement. A roster fetched at the moment of scanning would make the whole flow depend on a
 * network the app is designed to work without.
 *
 * Keyed on a surrogate id, because a day may hold more than one shift.
 *
 * It used to be keyed on the date, on the reasoning that a guard has exactly one duty per day. They
 * do not. The office splits shifts, covers absences, and overrides a rest day into work — and a
 * guard whose 06:00 was cancelled and replaced by a 15:00 legitimately holds two entries against
 * the same date. With the date as the primary key the second silently overwrote the first, so the
 * phone kept whichever the server happened to list last and no amount of signing out, back in, or
 * syncing could produce the other one. The roster was being collapsed on arrival, every time.
 *
 * The table is cleared and rewritten on every refresh, so the id is never referred to by anything
 * and never has to survive.
 */
@Entity(tableName = "schedule")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO date, `2026-07-12`. */
    val date: String,
    /** `SG` or `RG`. Stored as the server's code — it is the thing the rules are written against. */
    val dutyType: String,
    val dutyName: String?,
    val startsAt: String?,
    val endsAt: String?,
    val totalHours: Float,
    val updatedAt: Long,
)
