package com.appetiser.guardapp.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.appetiser.guardapp.core.common.Clock
import com.appetiser.guardapp.core.database.AttendanceDao
import com.appetiser.guardapp.core.database.AttendanceEntity
import com.appetiser.guardapp.core.network.ApiError
import com.appetiser.guardapp.core.network.ApiResult
import com.appetiser.guardapp.core.network.isRetriable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.math.min

/**
 * Drains the attendance queue: claim each eligible record, upload it, and move it to its
 * terminal or retriable state. This is the class the offline-first guarantee ultimately rests
 * on, so two failure modes get explicit handling.
 */
@HiltWorker
class AttendanceSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: AttendanceDao,
    private val uploader: AttendanceUploader,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = clock.nowMillis()

        // T-25: a crash mid-upload strands a record in SYNCING, which the claim predicate never
        // re-selects. Sweep those back to PENDING first, or they are lost forever.
        dao.reclaimStaleClaims(staleBefore = now - STALE_CLAIM_MS, now = now)

        var sawTransientFailure = false

        for (record in dao.eligibleForSync(now = now, limit = BATCH)) {
            // CAS claim. rows-affected 0 means another worker took it first — never double-send.
            if (dao.claim(record.id, now = clock.nowMillis()) == 0) continue

            when (val result = uploader.upload(record)) {
                is ApiResult.Success -> dao.markSynced(record.id, result.value.id, clock.nowMillis())

                is ApiResult.Failure -> when (val error = result.error) {
                    // The token is gone; the record owes nothing. Release the claim and stop —
                    // retrying now only produces more 401s. It syncs after re-authentication.
                    ApiError.Unauthorized -> {
                        dao.releaseClaim(record.id, clock.nowMillis())
                        return Result.success()
                    }

                    else -> if (error.isRetriable) {
                        sawTransientFailure = true
                        dao.markFailed(
                            id = record.id,
                            error = error.describe(),
                            nextAttemptAt = clock.nowMillis() + backoffMs(record),
                            now = clock.nowMillis(),
                        )
                    } else {
                        // Permanent (4xx, validation, oversize): retrying loops forever. Keep the
                        // record and its selfie, surface it as REJECTED.
                        dao.markRejected(record.id, error.describe(), clock.nowMillis())
                    }
                }
            }
        }

        // Ask WorkManager to run us again only for transient failures; permanent rejections and a
        // drained queue are both "done".
        return if (sawTransientFailure) Result.retry() else Result.success()
    }

    /** Exponential, capped: base * 2^retryCount. A burst of failures does not retry in lockstep. */
    private fun backoffMs(record: AttendanceEntity): Long {
        val exponent = min(record.retryCount, MAX_SHIFT)
        return min(BASE_BACKOFF_MS shl exponent, MAX_BACKOFF_MS)
    }

    private fun ApiError.describe(): String = when (this) {
        is ApiError.Rejected -> "$code: $message"
        is ApiError.Validation -> message
        is ApiError.Server -> "server $status: $message"
        is ApiError.Network -> "network: ${cause.message}"
        ApiError.PayloadTooLarge -> "selfie too large"
        ApiError.Unauthorized -> "unauthorized"
        ApiError.InvalidCredentials -> "invalid credentials"
        is ApiError.Unexpected -> cause.message ?: "unexpected error"
    }

    private companion object {
        const val BATCH = 20
        const val BASE_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 3 * 60 * 60 * 1000L // 3 hours
        const val MAX_SHIFT = 8
        // A SYNCING record older than this is assumed orphaned by a crash, not in flight.
        const val STALE_CLAIM_MS = 5 * 60 * 1000L
    }
}
