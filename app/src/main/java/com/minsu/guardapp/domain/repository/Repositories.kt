package com.minsu.guardapp.domain.repository

import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.model.Announcement
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.EvaluationQuestion
import com.minsu.guardapp.domain.model.GuardProfile
import kotlinx.coroutines.flow.Flow

/**
 * Every repository reads locally and refreshes separately. A failed refresh never clears the
 * cache: an offline guard must still be able to scan, acknowledge duties, and submit.
 */

interface CheckpointRepository {
    /** Resolves a scanned QR value against the local cache. Works offline. */
    suspend fun resolve(code: String): CheckpointResolution

    /** One cached checkpoint by id. Names the post a stationed guard timed in at. */
    suspend fun byId(id: Long): Checkpoint?

    fun observeActive(): Flow<List<Checkpoint>>

    suspend fun refresh(): ApiResult<Unit>
}

interface DutyRepository {
    /** The duties revision the guard must acknowledge. Null before the first refresh. */
    suspend fun activeDuty(): Duty?

    suspend fun refresh(): ApiResult<Unit>
}

interface SettingsRepository {
    /** Emits defaults until a refresh has succeeded at least once. */
    fun observe(): Flow<AppSettings>

    suspend fun current(): AppSettings

    suspend fun refresh(): ApiResult<Unit>
}

interface AttendanceRepository {
    /** Records that still owe the server an upload. Drives Home's pending badge. */
    fun observeUnsyncedCount(): Flow<Int>

    fun observeHistory(limit: Int = 50): Flow<List<AttendanceRecord>>

    /** One record, live: the detail screen follows its sync status as the worker runs. */
    fun observeRecord(id: String): Flow<AttendanceRecord?>

    /** Re-queues a failed or rejected record and requests a sync. No-op for other states. */
    suspend fun retry(id: String)

    /** Records the server would not take. Never hidden: a rejected attendance is the guard's problem to see. */
    fun observeStuckCount(): Flow<Int>

    /**
     * Re-queues every failed or rejected record and drains the queue now. Returns how many were
     * un-stuck, which is what "Sync now" reports back.
     */
    suspend fun syncNow(): Int

    /** Local records captured within [fromMillis, toMillis). The source for Reports. */
    fun observeInRange(fromMillis: Long, toMillis: Long): Flow<List<AttendanceRecord>>

    /**
     * Visits per checkpoint today, from this device, keyed by checkpoint id.
     *
     * A post the guard has not reached is simply absent from the map. Decides whether the round is
     * walked, and the rule is two visits to each post rather than two scans anywhere.
     */
    suspend fun checkpointVisitsToday(): Map<Long, Int>

    /**
     * The post this guard visited most recently today, or null if they have not visited one.
     *
     * Two visits to the same post back to back are not a patrol — they are a guard standing at one
     * door scanning it twice. The round requires them to go somewhere else in between.
     */
    suspend fun lastVisitedCheckpointToday(): Long?

    /**
     * Commits a captured attendance record to the local database, then requests a sync. The
     * record is durable the instant this returns — the write is local-first, never
     * network-first, so a valid attendance is never lost to connectivity.
     *
     * @param id the client-generated UUID that is also the idempotency key.
     */
    suspend fun submit(id: String, draft: AttendanceDraft)
}

interface ProfileRepository {
    /** Null until a profile has been fetched. Cached so Home renders a name offline. */
    fun observe(): Flow<GuardProfile?>

    suspend fun refresh(): ApiResult<Unit>

    suspend fun clear()
}

interface AnnouncementRepository {
    fun observe(): Flow<List<Announcement>>

    suspend fun refresh(): ApiResult<Unit>
}

interface AuthRepository {
    /** True while a session token is stored. Drives the auth gate. */
    val isAuthenticated: Flow<Boolean>

    suspend fun login(username: String, password: String): ApiResult<Unit>

    /** Clears the local session. Never touches the attendance queue. */
    suspend fun logout()
}

/**
 * The guard's duty roster.
 *
 * Cached, and read from the cache, because the scanner needs to know whether this guard is stationed
 * or roving *before* it can offer them the right buttons — and it needs to know that in a basement.
 */
interface ScheduleRepository {
    /** Today's duty. Null on a rest day, or a week the office has not filled in. */
    fun observeToday(): Flow<DutyAssignment?>

    /** Every rostered day the phone knows about, in order. What the guard is shown. */
    fun observeAll(): Flow<List<DutyAssignment>>

    suspend fun today(): DutyAssignment?

    /** False when nobody has joined this login to a guard on the roster. Not the same as "no shift". */
    val isLinked: Flow<Boolean>

    /**
     * The checkpoint this guard timed in at today, if they have.
     *
     * A stationed guard's post. Nobody assigns it — the first Time In of the day defines it, and the
     * shift must end where it began.
     */
    suspend fun postTimedInAtToday(): Long?

    suspend fun refresh(): ApiResult<Unit>
}

/**
 * The post-shift self-evaluation questions.
 *
 * Cached, because a guard closing a shift at a perimeter post has no more signal than one opening
 * it — and a Time Out they cannot complete is a shift they cannot clock out of.
 */
interface EvaluationRepository {
    /** In the order the office wants them asked. */
    suspend fun questions(): List<EvaluationQuestion>

    suspend fun refresh(): ApiResult<Unit>
}
