package com.appetiser.guardapp.data

import com.appetiser.guardapp.core.common.Clock
import com.appetiser.guardapp.core.database.AttendanceDao
import com.appetiser.guardapp.core.database.CheckpointDao
import com.appetiser.guardapp.core.database.DutyDao
import com.appetiser.guardapp.core.network.ApiErrorMapper
import com.appetiser.guardapp.core.network.ApiResult
import com.appetiser.guardapp.core.network.GuardApi
import com.appetiser.guardapp.core.network.map
import com.appetiser.guardapp.domain.model.AppSettings
import com.appetiser.guardapp.domain.model.AttendanceRecord
import com.appetiser.guardapp.domain.model.Checkpoint
import com.appetiser.guardapp.domain.model.CheckpointResolution
import com.appetiser.guardapp.domain.model.Duty
import com.appetiser.guardapp.domain.repository.AttendanceRepository
import com.appetiser.guardapp.domain.repository.CheckpointRepository
import com.appetiser.guardapp.domain.repository.DutyRepository
import com.appetiser.guardapp.domain.repository.SettingsRepository
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
) : AttendanceRepository {

    override fun observeUnsyncedCount(): Flow<Int> = dao.observeUnsyncedCount()

    override fun observeHistory(limit: Int): Flow<List<AttendanceRecord>> =
        dao.observePage(limit).map { entities -> entities.map { it.toDomain() } }
}
