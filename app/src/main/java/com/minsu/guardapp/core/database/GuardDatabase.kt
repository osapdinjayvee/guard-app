package com.minsu.guardapp.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The local source of truth. The network is a synchronization detail; the UI observes Room.
 *
 * This database holds unsynced attendance records and their selfies, so it must never be
 * dropped on a schema change — see [com.minsu.guardapp.core.di.DatabaseModule].
 */
@Database(
    entities = [
        AttendanceEntity::class,
        CheckpointEntity::class,
        DutyEntity::class,
        ScheduleEntity::class,
        AnnouncementEntity::class,
        EvaluationQuestionEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(SyncStatusConverter::class, AttendanceTypeConverter::class)
abstract class GuardDatabase : RoomDatabase() {
    abstract fun attendanceDao(): AttendanceDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun dutyDao(): DutyDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun announcementDao(): AnnouncementDao
    abstract fun evaluationQuestionDao(): EvaluationQuestionDao

    companion object {
        const val NAME = "guard.db"
    }
}
