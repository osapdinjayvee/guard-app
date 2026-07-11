package com.appetiser.guardapp.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.appetiser.guardapp.core.database.AttendanceDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Drains the attendance queue.
 *
 * **Placeholder body (T-23).** The record is durably in Room and enqueued; the real upload —
 * claim, POST /api/attendance, mark synced/failed/rejected, backoff, stale-claim reclaim —
 * lands in T-24. Until then this returns success without touching sync status, so records stay
 * PENDING and the Home badge honestly reflects that nothing has uploaded yet.
 */
@HiltWorker
class AttendanceSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @Suppress("unused") private val attendanceDao: AttendanceDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = Result.success()
}
