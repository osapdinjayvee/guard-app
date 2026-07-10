package com.appetiser.guardapp.domain.repository

import com.appetiser.guardapp.core.network.ApiResult
import com.appetiser.guardapp.domain.model.Announcement
import com.appetiser.guardapp.domain.model.AppSettings
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
