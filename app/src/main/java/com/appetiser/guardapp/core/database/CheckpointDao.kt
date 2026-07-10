package com.appetiser.guardapp.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckpointDao {

    @Upsert
    suspend fun upsertAll(checkpoints: List<CheckpointEntity>)

    /** Offline QR resolution. Returns null for unknown codes. */
    @Query("SELECT * FROM checkpoints WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): CheckpointEntity?

    @Query("SELECT * FROM checkpoints WHERE status = 'ACTIVE' ORDER BY name")
    fun observeActive(): Flow<List<CheckpointEntity>>

    @Query("SELECT COUNT(*) FROM checkpoints")
    suspend fun count(): Int
}
