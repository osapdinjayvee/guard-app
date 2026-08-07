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

    /**
     * Records downloaded from the server, to refill a device that has none — a reinstall, cleared
     * app data, a guard issued a new handset.
     *
     * IGNORE, emphatically not REPLACE. A row already here is either a capture still waiting to be
     * uploaded or one this device uploaded itself, and in both cases the local copy is the better
     * one: it knows the path to the photo on this phone and where the record sits in the sync
     * queue. Overwriting it with the server's view would mark a pending capture as SYNCED and drop
     * it from the queue unsent. The download fills gaps; it never overrules what is already here.
     *
     * Rows arrive keyed by their `client_uuid`, the same idempotency key they were captured under,
     * so re-downloading is free and a record uploaded from this device lands back on itself.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDownloaded(records: List<AttendanceEntity>): List<Long>

    @Query("SELECT * FROM attendance WHERE id = :id")
    suspend fun byId(id: String): AttendanceEntity?

    @Query("SELECT * FROM attendance WHERE id = :id")
    fun observeById(id: String): Flow<AttendanceEntity?>

    /** Records captured within a half-open range [from, to), newest first. Drives Reports. */
    @Query(
        """
        SELECT * FROM attendance
        WHERE userId = :userId
          AND capturedAt >= :fromMillis AND capturedAt < :toMillis
        ORDER BY capturedAt DESC
        """
    )
    fun observeInRange(userId: Long, fromMillis: Long, toMillis: Long): Flow<List<AttendanceEntity>>

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
    @Query(
        """
        SELECT COUNT(*) FROM attendance
        WHERE userId = :userId AND syncStatus IN ('FAILED', 'REJECTED')
        """
    )
    fun observeStuckCount(userId: Long): Flow<Int>

    /**
     * Only the permanently refused, without the merely failed.
     *
     * [observeStuckCount] deliberately counts both, because to a guard they look the same — nothing
     * is moving. They are not the same to the queue: a FAILED record has a transient cause and will
     * go up on the next pass, while a REJECTED one has been given a reason by the server and will
     * sit there until a human decides what to do with it. Discarding is offered on this count
     * alone, so a record that was only ever going to need one more attempt cannot be thrown away.
     */
    @Query(
        """
        SELECT COUNT(*) FROM attendance
        WHERE userId = :userId AND syncStatus = 'REJECTED'
        """
    )
    fun observeRejectedCount(userId: Long): Flow<Int>

    /** The rejected rows themselves — read before deleting, so their selfies can go with them. */
    @Query(
        """
        SELECT * FROM attendance
        WHERE userId = :userId AND syncStatus = 'REJECTED'
        """
    )
    suspend fun rejected(userId: Long): List<AttendanceEntity>

    /**
     * Throws away every rejected record for this guard. Irreversible, and meant to be.
     *
     * Scoped by guard for the same reason every read is: another account's stranded records are
     * their evidence, not this guard's to discard from a shared handset.
     */
    @Query(
        """
        DELETE FROM attendance
        WHERE userId = :userId AND syncStatus = 'REJECTED'
        """
    )
    suspend fun deleteRejected(userId: Long): Int

    @Query(
        """
        SELECT * FROM attendance WHERE userId = :userId
        ORDER BY capturedAt DESC LIMIT :limit OFFSET :offset
        """
    )
    fun observePage(userId: Long, limit: Int, offset: Int = 0): Flow<List<AttendanceEntity>>

    /** Home's pending badge. Anything that still owes the server a record. */
    @Query("SELECT COUNT(*) FROM attendance WHERE userId = :userId AND syncStatus IN (:statuses)")
    fun observeUnsyncedCount(
        userId: Long,
        statuses: List<SyncStatus> = SyncStatus.UNSYNCED,
    ): Flow<Int>

    /**
     * Unsynced records that belong to *another* signed-in guard, not the current one.
     *
     * The sync worker only ever uploads the current guard's records — a capture must never go up
     * under a different guard's token — so a record left behind by a previous account is stranded on
     * this device until its owner signs back in. It is invisible to [observeUnsyncedCount], which is
     * scoped to the current guard, so it is surfaced separately rather than silently stuck. NO_USER
     * (-1) orphans are excluded: those belong to nobody, not to another account.
     */
    @Query(
        """
        SELECT COUNT(*) FROM attendance
        WHERE userId != :userId AND userId != -1 AND syncStatus IN (:statuses)
        """
    )
    fun observeOtherAccountUnsyncedCount(
        userId: Long,
        statuses: List<SyncStatus> = SyncStatus.UNSYNCED,
    ): Flow<Int>

    @Query(
        """
        SELECT * FROM attendance
        WHERE userId = :userId
          AND syncStatus IN (:statuses)
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
        ORDER BY capturedAt ASC
        LIMIT :limit
        """
    )
    suspend fun eligibleForSync(
        userId: Long,
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
        WHERE userId = :userId
          AND attendanceType = 'TIME_IN'
          AND capturedAt >= :fromMillis AND capturedAt < :toMillis
        ORDER BY capturedAt ASC
        LIMIT 1
        """
    )
    suspend fun firstTimeInBetween(userId: Long, fromMillis: Long, toMillis: Long): AttendanceEntity?

    /**
     * The guard's Time Out for a given day, if they have one.
     *
     * A shift has a single Time Out; once it exists the shift is closed, and scanning again should
     * say so rather than offer a second Time Out. Counted locally, like every other rule here, so it
     * holds at a perimeter post with no signal. A rejected record still counts — the guard did time
     * out; a rejection is a problem to surface, not a reason to re-open a finished shift.
     */
    @Query(
        """
        SELECT * FROM attendance
        WHERE userId = :userId
          AND attendanceType = 'TIME_OUT'
          AND capturedAt >= :fromMillis AND capturedAt < :toMillis
        ORDER BY capturedAt ASC
        LIMIT 1
        """
    )
    suspend fun firstTimeOutBetween(userId: Long, fromMillis: Long, toMillis: Long): AttendanceEntity?

    /**
     * Patrol visits per post in a window — the round, as this phone knows it.
     *
     * Grouped by checkpoint, because the rule is two visits to *each* post rather than two scans
     * anywhere. Counted locally, not asked of the server: a guard finishing a round in a dead spot
     * still has every scan on the handset, and gating their Time Out on a network call would strand
     * them at the end of a shift.
     *
     * The Time In counts as a visit to the post it was taken at. The guard stood there and the
     * selfie proves it, and counting only checkpoint scans left the post where the shift opened
     * permanently one visit behind every other — which, since the same post cannot be scanned twice
     * in a row, meant a detour at the end of an eight-hour round to satisfy a requirement already
     * met in substance. The server applies the same rule; if these two ever disagree, the app
     * offers a Time Out the server then refuses.
     *
     * A Time Out is not counted: it cannot contribute to the round it is asking permission for.
     */
    @Query(
        """
        SELECT checkpointId AS checkpointId, COUNT(*) AS visits FROM attendance
        WHERE userId = :userId
          AND attendanceType IN ('CHECKPOINT', 'TIME_IN')
          AND capturedAt >= :fromMillis AND capturedAt < :toMillis
        GROUP BY checkpointId
        """
    )
    suspend fun checkpointVisitCountsBetween(
        userId: Long,
        fromMillis: Long,
        toMillis: Long,
    ): List<CheckpointVisitCount>

    /**
     * The last post this guard visited today, if any.
     *
     * Two visits to the same post back to back are not a patrol — they are a guard standing still.
     * The round requires the guard to go somewhere else in between.
     */
    @Query(
        """
        SELECT checkpointId FROM attendance
        WHERE userId = :userId
          AND attendanceType = 'CHECKPOINT'
          AND capturedAt >= :fromMillis AND capturedAt < :toMillis
        ORDER BY capturedAt DESC
        LIMIT 1
        """
    )
    suspend fun lastVisitedCheckpointBetween(userId: Long, fromMillis: Long, toMillis: Long): Long?

    @Query("SELECT COUNT(*) FROM attendance")
    suspend fun count(): Int
}

/** One post and how many times it was visited. Projection for [AttendanceDao.checkpointVisitCountsBetween]. */
data class CheckpointVisitCount(
    val checkpointId: Long,
    val visits: Int,
)
