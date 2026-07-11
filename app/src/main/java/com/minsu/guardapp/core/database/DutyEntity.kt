package com.minsu.guardapp.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Cached duties revision. Kept locally so the acknowledgement gate works offline; the [id] is
 * submitted with the attendance record as `duties_version_id`, so an acknowledgement always
 * says *which* revision was read.
 */
@Entity(tableName = "duties")
data class DutyEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val content: String,
    val active: Boolean,
    val effectiveDate: String?,
    val updatedAt: Long,
)

@Dao
interface DutyDao {

    @Upsert
    suspend fun upsert(duty: DutyEntity)

    @Query("SELECT * FROM duties WHERE active = 1 ORDER BY id DESC LIMIT 1")
    suspend fun activeDuty(): DutyEntity?
}
