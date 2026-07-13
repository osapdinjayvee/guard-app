package com.minsu.guardapp.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {

    @Upsert
    suspend fun upsertAll(entries: List<ScheduleEntity>)

    /** The duty for one day. Null on a rest day, or a week the office has not filled in. */
    @Query("SELECT * FROM schedule WHERE date = :date LIMIT 1")
    fun observeForDate(date: String): Flow<ScheduleEntity?>

    @Query("SELECT * FROM schedule WHERE date = :date LIMIT 1")
    suspend fun forDate(date: String): ScheduleEntity?

    @Query("SELECT * FROM schedule ORDER BY date")
    fun observeAll(): Flow<List<ScheduleEntity>>

    /**
     * The roster is replaced wholesale on every refresh, not merged.
     *
     * A shift the office *removed* has to disappear from the phone. Upserting alone would leave a
     * cancelled duty sitting in the cache forever, and the scanner would go on offering a guard
     * buttons for a shift that no longer exists.
     */
    @Query("DELETE FROM schedule")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM schedule")
    suspend fun count(): Int
}
