package com.minsu.guardapp.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckpointDao {

    @Upsert
    suspend fun upsertAll(checkpoints: List<CheckpointEntity>)

    /**
     * Offline QR resolution. Returns null when the cache has never heard of this code — which is
     * not the same as the code being invalid, and the caller must not conflate them.
     *
     * Matches the code *or* the slug, case-insensitively. What a guard scans is whatever was
     * printed on a sticker and taped to a wall, possibly a year ago and possibly before anyone
     * settled what the payload should be. `=` in SQLite is case-sensitive, so an otherwise perfect
     * `clinic` would miss a stored `CLINIC` and the guard would be told their checkpoint does not
     * exist.
     */
    @Query(
        """
        SELECT * FROM checkpoints
        WHERE code = :code COLLATE NOCASE OR slug = :code COLLATE NOCASE
        LIMIT 1
        """
    )
    suspend fun findByCode(code: String): CheckpointEntity?

    @Query("SELECT * FROM checkpoints WHERE status = 'ACTIVE' ORDER BY name")
    fun observeActive(): Flow<List<CheckpointEntity>>

    /** Used to name the post a stationed guard timed in at, from its id alone. */
    @Query("SELECT * FROM checkpoints WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CheckpointEntity?

    @Query("SELECT COUNT(*) FROM checkpoints")
    suspend fun count(): Int
}
