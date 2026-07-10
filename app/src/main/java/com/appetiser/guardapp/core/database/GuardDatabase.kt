package com.appetiser.guardapp.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The local source of truth. The network is a synchronization detail; the UI observes Room.
 *
 * This database holds unsynced attendance records and their selfies, so it must never be
 * dropped on a schema change — see [com.appetiser.guardapp.core.di.DatabaseModule].
 */
@Database(
    entities = [
        AttendanceEntity::class,
        CheckpointEntity::class,
        DutyEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(SyncStatusConverter::class, AttendanceTypeConverter::class)
abstract class GuardDatabase : RoomDatabase() {
    abstract fun attendanceDao(): AttendanceDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun dutyDao(): DutyDao

    companion object {
        const val NAME = "guard.db"
    }
}
