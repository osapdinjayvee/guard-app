package com.minsu.guardapp.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * An announcement from the office, cached.
 *
 * Everything else the app shows survives a restart with no network — the profile, the checkpoints,
 * the duties, the roster. Announcements did not: they were held in memory, so they were there for as
 * long as the process lived and gone the moment it did not. A guard who read "gate 3 closed today"
 * on the way in, and reopened the app at their post with no signal, found nothing.
 *
 * An announcement is the office telling a guard something they need to know on shift. It belongs on
 * the phone, like the rest of it.
 */
@Entity(tableName = "announcements")
data class AnnouncementEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val content: String,
    val updatedAt: Long,
)

@Dao
interface AnnouncementDao {

    @Upsert
    suspend fun upsertAll(announcements: List<AnnouncementEntity>)

    @Query("SELECT * FROM announcements ORDER BY id DESC")
    fun observeAll(): Flow<List<AnnouncementEntity>>

    /**
     * Replaced wholesale on refresh, not merged. An announcement the office *withdrew* has to
     * disappear from the phone — a stale notice is worse than none when it says a gate is open that
     * has since been closed.
     */
    @Query("DELETE FROM announcements")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM announcements")
    suspend fun count(): Int
}
