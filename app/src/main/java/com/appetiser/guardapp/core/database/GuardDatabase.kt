package com.appetiser.guardapp.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The local source of truth. The network is a synchronization detail; the UI observes Room.
 *
 * The attendance queue, duties cache, and session tables arrive with the full schema in T-11.
 */
@Database(
    entities = [CheckpointEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GuardDatabase : RoomDatabase() {
    abstract fun checkpointDao(): CheckpointDao

    companion object {
        const val NAME = "guard.db"
    }
}
