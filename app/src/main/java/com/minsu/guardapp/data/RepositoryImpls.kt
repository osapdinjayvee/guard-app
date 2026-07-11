package com.minsu.guardapp.data

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.AttendanceDao
import com.minsu.guardapp.core.database.AttendanceEntity
import com.minsu.guardapp.core.database.SyncStatus
import com.minsu.guardapp.core.database.AttendanceType as EntityAttendanceType
import com.minsu.guardapp.core.database.CheckpointDao
import com.minsu.guardapp.core.database.DutyDao
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.map
import com.minsu.guardapp.core.sync.SyncScheduler
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.repository.DutyRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCheckpointRepository @Inject constructor(
    private val dao: CheckpointDao,
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
    private val clock: Clock,
) : CheckpointRepository {

    /**
     * Reads the local cache only. Requiring a round trip here would break offline scanning —
     * a guard at a basement checkpoint has no signal, and the server re-validates at sync time.
     */
    override suspend fun resolve(code: String): CheckpointResolution {
        val entity = dao.findByCode(code) ?: return CheckpointResolution.Unknown(code)
        val checkpoint = entity.toDomain()
        return if (checkpoint.isActive) {
            CheckpointResolution.Resolved(checkpoint)
        } else {
            CheckpointResolution.Disabled(checkpoint)
        }
    }

    override fun observeActive(): Flow<List<Checkpoint>> =
        dao.observeActive().map { entities -> entities.map { it.toDomain() } }

    /** A failed refresh leaves the cache untouched: stale checkpoints beat none. */
    override suspend fun refresh(): ApiResult<Unit> =
        errors.call { api.checkpoints().data }
            .also { result ->
                if (result is ApiResult.Success) {
                    val now = clock.nowMillis()
                    dao.upsertAll(result.value.map { it.toEntity(now) })
                }
            }
            .map { }
}

@Singleton
class DefaultDutyRepository @Inject constructor(
    private val dao: DutyDao,
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
    private val clock: Clock,
) : DutyRepository {

    override suspend fun activeDuty(): Duty? = dao.activeDuty()?.toDomain()

    override suspend fun refresh(): ApiResult<Unit> =
        errors.call { api.duties().data }
            .also { result ->
                if (result is ApiResult.Success) {
                    dao.upsert(result.value.toEntity(clock.nowMillis()))
                }
            }
            .map { }
}

@Singleton
class DefaultSettingsRepository @Inject constructor(
    private val cache: SettingsCache,
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
) : SettingsRepository {

    override fun observe(): Flow<AppSettings> = cache.observe()

    override suspend fun current(): AppSettings = cache.observe().first()

    override suspend fun refresh(): ApiResult<Unit> =
        errors.call { api.settings().data }
            .also { result ->
                if (result is ApiResult.Success) cache.save(result.value.toDomain())
            }
            .map { }
}

@Singleton
class DefaultAttendanceRepository @Inject constructor(
    private val dao: AttendanceDao,
    private val profiles: ProfileRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : AttendanceRepository {

    override fun observeUnsyncedCount(): Flow<Int> = dao.observeUnsyncedCount()

    override fun observeHistory(limit: Int): Flow<List<AttendanceRecord>> =
        dao.observePage(limit).map { entities -> entities.map { it.toDomain() } }

    override fun observeRecord(id: String): Flow<AttendanceRecord?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun retry(id: String) {
        // Only re-queue if the record was actually in a retriable-by-hand state; requesting a
        // sync for a record that did not move would just spin the worker for nothing.
        if (dao.requeue(id, clock.nowMillis()) > 0) syncScheduler.requestSync()
    }

    override fun observeStuckCount(): Flow<Int> = dao.observeStuckCount()

    /**
     * Unlike [retry], this always drains — even when nothing was stuck. The queue may hold records
     * that are merely PENDING behind a network that has just come back, and the guard tapping the
     * button is entitled to see them go.
     */
    override suspend fun syncNow(): Int {
        val requeued = dao.requeueAll(clock.nowMillis())
        syncScheduler.syncNow()
        return requeued
    }

    override fun observeInRange(fromMillis: Long, toMillis: Long): Flow<List<AttendanceRecord>> =
        dao.observeInRange(fromMillis, toMillis).map { entities -> entities.map { it.toDomain() } }

    override suspend fun submit(id: String, draft: AttendanceDraft) {
        val now = clock.nowMillis()
        // Insert first, then enqueue. If the insert throws the record is not committed and no
        // sync is scheduled; if enqueue somehow fails the record is still safely PENDING and
        // the periodic drain (T-24) picks it up.
        dao.insert(
            AttendanceEntity(
                id = id,
                userId = profiles.observe().first()?.id ?: 0L,
                checkpointId = draft.checkpointId,
                checkpointCode = draft.checkpointCode,
                attendanceType = when (draft.type) {
                    AttendanceType.TIME_IN -> EntityAttendanceType.TIME_IN
                    AttendanceType.TIME_OUT -> EntityAttendanceType.TIME_OUT
                },
                selfiePath = draft.selfiePath,
                capturedAt = draft.capturedAtMillis,
                latitude = draft.latitude,
                longitude = draft.longitude,
                accuracy = draft.accuracyMetres,
                dutiesAcknowledged = true,
                dutiesVersionId = draft.dutiesVersionId,
                deviceId = null,
                syncStatus = SyncStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            )
        )
        syncScheduler.requestSync()
    }
}
