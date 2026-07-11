package com.minsu.guardapp.core.di

import android.content.Context
import androidx.room.Room
import com.minsu.guardapp.core.database.AttendanceDao
import com.minsu.guardapp.core.database.CheckpointDao
import com.minsu.guardapp.core.database.DutyDao
import com.minsu.guardapp.core.database.GUARD_MIGRATIONS
import com.minsu.guardapp.core.database.GuardDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): GuardDatabase =
        Room.databaseBuilder(context, GuardDatabase::class.java, GuardDatabase.NAME)
            // No fallbackToDestructiveMigration: this database holds unsynced attendance
            // records, and dropping it on a schema change would destroy captured evidence.
            .addMigrations(*GUARD_MIGRATIONS)
            .build()

    @Provides
    fun checkpointDao(database: GuardDatabase): CheckpointDao = database.checkpointDao()

    @Provides
    fun attendanceDao(database: GuardDatabase): AttendanceDao = database.attendanceDao()

    @Provides
    fun dutyDao(database: GuardDatabase): DutyDao = database.dutyDao()
}
