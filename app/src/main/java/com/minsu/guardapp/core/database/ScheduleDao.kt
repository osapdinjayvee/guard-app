package com.minsu.guardapp.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {

    @Upsert
    suspend fun upsertAll(entries: List<ScheduleEntity>)

    /**
     * Every shift on one day, earliest first. Empty on a rest day, or a week the office has not
     * filled in.
     *
     * A list, not a row. These returned `LIMIT 1` and the caller took it as "the" duty, which threw
     * away the second shift of a split day and gave whichever the database listed first.
     */
    @Query("SELECT * FROM schedule WHERE date = :date ORDER BY startsAt, id")
    fun observeForDate(date: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedule WHERE date = :date ORDER BY startsAt, id")
    suspend fun forDate(date: String): List<ScheduleEntity>

    /**
     * Yesterday and today, because a shift is not a day.
     *
     * A guard rostered 23:00–07:00 is still working at 00:30, and the entry that says so is filed
     * against yesterday. Asking for today alone is what made the shift disappear at midnight.
     * Ordered by date first so the caller's "last one filed today" fallback still means today's.
     */
    @Query("SELECT * FROM schedule WHERE date IN (:dates) ORDER BY date, startsAt, id")
    fun observeForDates(dates: List<String>): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedule WHERE date IN (:dates) ORDER BY date, startsAt, id")
    suspend fun forDates(dates: List<String>): List<ScheduleEntity>

    @Query("SELECT * FROM schedule ORDER BY date, startsAt, id")
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
