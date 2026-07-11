package com.appetiser.guardapp.domain.repository

import com.appetiser.guardapp.core.network.ApiResult
import com.appetiser.guardapp.domain.model.Announcement
import com.appetiser.guardapp.domain.model.AppSettings
import com.appetiser.guardapp.domain.model.AttendanceDraft
import com.appetiser.guardapp.domain.model.AttendanceRecord
import com.appetiser.guardapp.domain.model.Checkpoint
import com.appetiser.guardapp.domain.model.CheckpointResolution
import com.appetiser.guardapp.domain.model.Duty
import com.appetiser.guardapp.domain.model.GuardProfile
import kotlinx.coroutines.flow.Flow

/**
 * Every repository reads locally and refreshes separately. A failed refresh never clears the
 * cache: an offline guard must still be able to scan, acknowledge duties, and submit.
 */

interface CheckpointRepository {
    /** Resolves a scanned QR value against the local cache. Works offline. */
    suspend fun resolve(code: String): CheckpointResolution

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

    /** Local records captured within [fromMillis, toMillis). The source for Reports. */
    fun observeInRange(fromMillis: Long, toMillis: Long): Flow<List<AttendanceRecord>>

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
