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
 * Keyed on the date, because a guard has exactly one duty per day.
 */
@Entity(tableName = "schedule")
data class ScheduleEntity(
    /** ISO date, `2026-07-12`. The natural key: one duty per guard per day. */
    @PrimaryKey val date: String,
    /** `SG` or `RG`. Stored as the server's code — it is the thing the rules are written against. */
    val dutyType: String,
    val dutyName: String?,
    val startsAt: String?,
    val endsAt: String?,
    val totalHours: Float,
    val updatedAt: Long,
)
