package com.minsu.guardapp.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {

    /**
     * ABORT, not REPLACE. The id is the idempotency key; a colliding insert is a bug, and
     * replacing would silently discard a captured record.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: AttendanceEntity)

    @Query("SELECT * FROM attendance WHERE id = :id")
    suspend fun byId(id: String): AttendanceEntity?

    @Query("SELECT * FROM attendance WHERE id = :id")
    fun observeById(id: String): Flow<AttendanceEntity?>

    /** Records captured within a half-open range [from, to), newest first. Drives Reports. */
    @Query(
        """
        SELECT * FROM attendance
        WHERE capturedAt >= :fromMillis AND capturedAt < :toMillis
        ORDER BY capturedAt DESC
        """
    )
    fun observeInRange(fromMillis: Long, toMillis: Long): Flow<List<AttendanceEntity>>

    /**
     * Manual retry of a failed or rejected record: back to PENDING, backoff cleared, so the next
     * sync claims it immediately. A REJECTED record only ever leaves that state this way — by the
     * guard explicitly asking, never automatically.
     */
    @Query(
        """
        UPDATE attendance
        SET syncStatus = 'PENDING', nextAttemptAt = NULL, lastError = NULL, updatedAt = :now
        WHERE id = :id AND syncStatus IN ('FAILED', 'REJECTED')
        """
    )
    suspend fun requeue(id: String, now: Long): Int

    /**
     * The same escape hatch, for every stuck record at once: what "Sync now" is actually for.
     *
     * A guard who taps it is telling us the thing that blocked these records — no signal, a lapsed
     * token, a checkpoint an admin had disabled and has now fixed — is over. Backoff is cleared so
     * the next pass claims them immediately rather than honouring a wait that was calculated for a
     * world that no longer exists.
     */
    @Query(
        """
        UPDATE attendance
        SET syncStatus = 'PENDING', nextAttemptAt = NULL, lastError = NULL, updatedAt = :now
        WHERE syncStatus IN ('FAILED', 'REJECTED')
        """
    )
    suspend fun requeueAll(now: Long): Int

    /** Records the server has refused or failed to take. Surfaced so they are never silent. */
    @Query("SELECT COUNT(*) FROM attendance WHERE syncStatus IN ('FAILED', 'REJECTED')")
    fun observeStuckCount(): Flow<Int>

    @Query("SELECT * FROM attendance ORDER BY capturedAt DESC LIMIT :limit OFFSET :offset")
    fun observePage(limit: Int, offset: Int = 0): Flow<List<AttendanceEntity>>

    /** Home's pending badge. Anything that still owes the server a record. */
    @Query("SELECT COUNT(*) FROM attendance WHERE syncStatus IN (:statuses)")
    fun observeUnsyncedCount(
        statuses: List<SyncStatus> = SyncStatus.UNSYNCED,
    ): Flow<Int>

    @Query(
        """
        SELECT * FROM attendance
        WHERE syncStatus IN (:statuses)
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
        ORDER BY capturedAt ASC
        LIMIT :limit
        """
    )
    suspend fun eligibleForSync(
        now: Long,
        limit: Int = 20,
        statuses: List<SyncStatus> = SyncStatus.CLAIMABLE,
    ): List<AttendanceEntity>

    /**
     * Compare-and-swap claim. Returns rows affected: 1 if this caller owns the upload, 0 if
     * another worker claimed it first or the row already reached a terminal state.
     *
     * This is what prevents two concurrent workers from double-sending the same record.
     */
    @Query(
        """
        UPDATE attendance
        SET syncStatus = 'SYNCING', claimedAt = :now, updatedAt = :now
        WHERE id = :id AND syncStatus IN (:statuses)
        """
    )
    suspend fun claim(
        id: String,
        now: Long,
        statuses: List<SyncStatus> = SyncStatus.CLAIMABLE,
    ): Int

    /**
     * Returns rows stranded in SYNCING by a process death back to PENDING.
     *
     * Without this, the CAS predicate above never re-selects them — they are neither PENDING
     * nor FAILED — and the record is lost silently. That is the exact outcome the offline-first
     * design exists to prevent, so this sweep runs at app start and before each sync pass.
     */
    @Query(
        """
        UPDATE attendance
        SET syncStatus = 'PENDING', claimedAt = NULL, updatedAt = :now
        WHERE syncStatus = 'SYNCING' AND claimedAt IS NOT NULL AND claimedAt <= :staleBefore
        """
    )
    suspend fun reclaimStaleClaims(staleBefore: Long, now: Long): Int

    @Query(
        """
        UPDATE attendance
        SET syncStatus = 'SYNCED', serverId = :serverId, claimedAt = NULL,
            nextAttemptAt = NULL, lastError = NULL, updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun markSynced(id: String, serverId: Long, now: Long)

    /** Transient failure: retriable, with backoff. */
    @Query(
        """
        UPDATE attendance
        SET syncStatus = 'FAILED', retryCount = retryCount + 1, claimedAt = NULL,
            nextAttemptAt = :nextAttemptAt, lastError = :error, updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun markFailed(id: String, error: String, nextAttemptAt: Long, now: Long)

    /**
     * Permanent rejection (a 4xx). Terminal: `nextAttemptAt` stays null so no automatic retry
     * picks it up, but the row and its selfie are kept and surfaced to the guard.
     */
    @Query(
        """
        UPDATE attendance
        SET syncStatus = 'REJECTED', claimedAt = NULL, nextAttemptAt = NULL,
            lastError = :error, updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun markRejected(id: String, error: String, now: Long)

    /**
     * Returns a claimed record to PENDING without touching retryCount or backoff. Used when a
     * 401 aborts sync: the record owes nothing, it just has to wait for re-authentication.
     */
    @Query(
        """
        UPDATE attendance
        SET syncStatus = 'PENDING', claimedAt = NULL, updatedAt = :now
        WHERE id = :id AND syncStatus = 'SYNCING'
        """
    )
    suspend fun releaseClaim(id: String, now: Long)

    /**
     * Where this guard first timed in on a given day, if they have.
     *
     * This is what makes a stationed guard's post knowable without anyone assigning one: the first
     * Time In of the day *is* the post, and every later record that day must agree with it. Read
     * from the local database, not the server, so the rule holds in a basement.
     *
     * A record that was rejected is deliberately still counted: it happened, the guard was there,
     * and pretending otherwise would let them quietly re-open the shift somewhere else.
     */
    @Query(
        """
        SELECT * FROM attendance
        WHERE attendanceType = 'TIME_IN'
          AND capturedAt >= :fromMillis AND capturedAt < :toMillis
        ORDER BY capturedAt ASC
        LIMIT 1
        """
    )
    suspend fun firstTimeInBetween(fromMillis: Long, toMillis: Long): AttendanceEntity?

    @Query("SELECT COUNT(*) FROM attendance")
    suspend fun count(): Int
}
