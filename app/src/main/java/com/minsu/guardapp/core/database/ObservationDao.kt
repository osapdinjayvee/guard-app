package com.minsu.guardapp.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The observation queue. Its sync queries mirror [AttendanceDao] one for one — the claim, the stale
 * sweep, the terminal states — and the reasoning written there applies here unchanged.
 */
@Dao
abstract class ObservationDao {

    /** ABORT, not REPLACE: the id is the idempotency key, and replacing would discard a report. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertObservation(observation: ObservationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPhotos(photos: List<ObservationPhotoEntity>)

    /**
     * The observation and its photos, together or not at all. An observation committed without its
     * photos could never be accepted — the server requires at least one — and would sit rejected.
     */
    @Transaction
    open suspend fun insert(observation: ObservationEntity, photos: List<ObservationPhotoEntity>) {
        insertObservation(observation)
        insertPhotos(photos)
    }

    @Transaction
    @Query(
        """
        SELECT * FROM observations WHERE userId = :userId
        ORDER BY capturedAt DESC LIMIT :limit
        """
    )
    abstract fun observePage(userId: Long, limit: Int): Flow<List<ObservationWithPhotos>>

    @Query("SELECT COUNT(*) FROM observations WHERE userId = :userId AND syncStatus IN (:statuses)")
    abstract fun observeUnsyncedCount(
        userId: Long,
        statuses: List<SyncStatus> = SyncStatus.UNSYNCED,
    ): Flow<Int>

    @Transaction
    @Query(
        """
        SELECT * FROM observations
        WHERE userId = :userId
          AND syncStatus IN (:statuses)
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
        ORDER BY capturedAt ASC
        LIMIT :limit
        """
    )
    abstract suspend fun eligibleForSync(
        userId: Long,
        now: Long,
        limit: Int = 20,
        statuses: List<SyncStatus> = SyncStatus.CLAIMABLE,
    ): List<ObservationWithPhotos>

    /** Compare-and-swap claim: 1 if this caller owns the upload, 0 if someone else got there first. */
    @Query(
        """
        UPDATE observations
        SET syncStatus = 'SYNCING', claimedAt = :now, updatedAt = :now
        WHERE id = :id AND syncStatus IN (:statuses)
        """
    )
    abstract suspend fun claim(
        id: String,
        now: Long,
        statuses: List<SyncStatus> = SyncStatus.CLAIMABLE,
    ): Int

    /** Returns rows stranded in SYNCING by a process death to PENDING, or they are never sent. */
    @Query(
        """
        UPDATE observations
        SET syncStatus = 'PENDING', claimedAt = NULL, updatedAt = :now
        WHERE syncStatus = 'SYNCING' AND claimedAt IS NOT NULL AND claimedAt <= :staleBefore
        """
    )
    abstract suspend fun reclaimStaleClaims(staleBefore: Long, now: Long): Int

    @Query(
        """
        UPDATE observations
        SET syncStatus = 'SYNCED', serverId = :serverId, claimedAt = NULL,
            nextAttemptAt = NULL, lastError = NULL, updatedAt = :now
        WHERE id = :id
        """
    )
    abstract suspend fun markSynced(id: String, serverId: Long, now: Long)

    @Query(
        """
        UPDATE observations
        SET syncStatus = 'FAILED', retryCount = retryCount + 1, claimedAt = NULL,
            nextAttemptAt = :nextAttemptAt, lastError = :error, updatedAt = :now
        WHERE id = :id
        """
    )
    abstract suspend fun markFailed(id: String, error: String, nextAttemptAt: Long, now: Long)

    @Query(
        """
        UPDATE observations
        SET syncStatus = 'REJECTED', claimedAt = NULL, nextAttemptAt = NULL,
            lastError = :error, updatedAt = :now
        WHERE id = :id
        """
    )
    abstract suspend fun markRejected(id: String, error: String, now: Long)

    @Query(
        """
        UPDATE observations
        SET syncStatus = 'PENDING', claimedAt = NULL, updatedAt = :now
        WHERE id = :id AND syncStatus = 'SYNCING'
        """
    )
    abstract suspend fun releaseClaim(id: String, now: Long)

    /** Manual retry of a failed or rejected observation. */
    @Query(
        """
        UPDATE observations
        SET syncStatus = 'PENDING', nextAttemptAt = NULL, lastError = NULL, updatedAt = :now
        WHERE id = :id AND syncStatus IN ('FAILED', 'REJECTED')
        """
    )
    abstract suspend fun requeue(id: String, now: Long): Int

    /** "Sync now" re-queues stuck observations along with stuck attendance. */
    @Query(
        """
        UPDATE observations
        SET syncStatus = 'PENDING', nextAttemptAt = NULL, lastError = NULL, updatedAt = :now
        WHERE syncStatus IN ('FAILED', 'REJECTED')
        """
    )
    abstract suspend fun requeueAll(now: Long): Int
}
