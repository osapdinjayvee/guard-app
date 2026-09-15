package com.minsu.guardapp.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.ObservationDao
import com.minsu.guardapp.core.database.ObservationEntity
import com.minsu.guardapp.core.network.ApiError
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.isRetriable
import com.minsu.guardapp.domain.repository.ProfileRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlin.math.min

/**
 * Drains the observation queue. The same state machine as [AttendanceSyncWorker] — claim, upload,
 * settle — with its own unique work, so a large batch of photos never holds up attendance.
 */
@HiltWorker
class ObservationSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: ObservationDao,
    private val uploader: ObservationUploader,
    private val profiles: ProfileRepository,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = clock.nowMillis()
        dao.reclaimStaleClaims(staleBefore = now - STALE_CLAIM_MS, now = now)

        // Only the signed-in guard's reports go up, under their own token. See AttendanceSyncWorker.
        val userId = profiles.observe().first()?.id ?: return Result.success()

        var sawTransientFailure = false

        for (record in dao.eligibleForSync(userId = userId, now = now, limit = BATCH)) {
            val observation = record.observation
            if (dao.claim(observation.id, now = clock.nowMillis()) == 0) continue

            when (val result = uploader.upload(record)) {
                is ApiResult.Success -> dao.markSynced(observation.id, result.value.id, clock.nowMillis())

                is ApiResult.Failure -> when (val error = result.error) {
                    ApiError.Unauthorized -> {
                        dao.releaseClaim(observation.id, clock.nowMillis())
                        return Result.success()
                    }

                    else -> if (error.isRetriable) {
                        sawTransientFailure = true
                        dao.markFailed(
                            id = observation.id,
                            error = error.describe(),
                            nextAttemptAt = clock.nowMillis() + backoffMs(observation),
                            now = clock.nowMillis(),
                        )
                    } else {
                        dao.markRejected(observation.id, error.describe(), clock.nowMillis())
                    }
                }
            }
        }

        return if (sawTransientFailure) Result.retry() else Result.success()
    }

    private fun backoffMs(observation: ObservationEntity): Long {
        val exponent = min(observation.retryCount, MAX_SHIFT)
        return min(BASE_BACKOFF_MS shl exponent, MAX_BACKOFF_MS)
    }

    private fun ApiError.describe(): String = when (this) {
        is ApiError.Rejected -> "$code: $message"
        is ApiError.Validation -> message
        is ApiError.Server -> "server $status: $message"
        is ApiError.Network -> "network: ${cause.message}"
        ApiError.PayloadTooLarge -> "photos too large"
        ApiError.NotFound -> "the server does not accept observations yet"
        ApiError.Unauthorized -> "unauthorized"
        ApiError.InvalidCredentials -> "invalid credentials"
        is ApiError.Unexpected -> cause.message ?: "unexpected error"
    }

    private companion object {
        const val BATCH = 10
        const val BASE_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 3 * 60 * 60 * 1000L
        const val MAX_SHIFT = 8
        const val STALE_CLAIM_MS = 5 * 60 * 1000L
    }
}
