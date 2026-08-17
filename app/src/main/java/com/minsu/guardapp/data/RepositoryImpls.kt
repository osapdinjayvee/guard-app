package com.minsu.guardapp.data

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.AttendanceDao
import com.minsu.guardapp.core.database.AttendanceEntity
import com.minsu.guardapp.core.database.SyncStatus
import com.minsu.guardapp.core.database.AttendanceType as EntityAttendanceType
import com.minsu.guardapp.core.database.CheckpointDao
import com.minsu.guardapp.core.database.CheckpointEntity
import com.minsu.guardapp.core.database.DutyDao
import com.minsu.guardapp.core.network.ApiError
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.map
import com.minsu.guardapp.core.sync.SyncScheduler
import com.minsu.guardapp.domain.model.ShiftWindow
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.domain.model.SyncOutcome
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.repository.DutyRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Calendar
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
     * Cache first, then the server — never the other way round.
     *
     * The cache answers instantly and works in a basement, which is where the scanning happens, so
     * a round trip is never on the critical path of a checkpoint the guard has scanned before.
     *
     * But a cache *miss* is not an answer. It means only that this device has not been told about
     * this checkpoint: a cold install, a checkpoint an admin added this morning, a refresh that
     * failed. Reporting that as "not a checkpoint in this system" is a lie, and an expensive one —
     * it sends a guard looking for another door, or convinces them the sticker on the wall is
     * broken. So on a miss the server is asked, and only a 404 from the server — the one authority
     * that can actually know — licenses that sentence. Anything else is [Unverifiable]: we could
     * not check, and we say so.
     */
    override suspend fun resolve(code: String): CheckpointResolution {
        val scanned = code.trim()
        if (scanned.isEmpty()) return CheckpointResolution.Unknown(code)

        dao.findByCode(scanned)?.let { return it.toResolution() }

        return when (val result = errors.call { api.checkpoint(scanned).data }) {
            is ApiResult.Success -> {
                // Cache it on the way past: the next guard to scan this checkpoint, offline,
                // resolves it from here.
                val entity = result.value.toEntity(clock.nowMillis())
                dao.upsertAll(listOf(entity))
                entity.toResolution()
            }

            is ApiResult.Failure -> when (result.error) {
                ApiError.NotFound -> CheckpointResolution.Unknown(scanned)
                else -> CheckpointResolution.Unverifiable(scanned)
            }
        }
    }

    private fun CheckpointEntity.toResolution(): CheckpointResolution {
        val checkpoint = toDomain()
        return if (checkpoint.isActive) {
            CheckpointResolution.Resolved(checkpoint)
        } else {
            CheckpointResolution.Disabled(checkpoint)
        }
    }

    override suspend fun byId(id: Long): Checkpoint? = dao.findById(id)?.toDomain()

    override fun observeActive(): Flow<List<Checkpoint>> =
        dao.observeActive().map { entities -> entities.map { it.toDomain() } }

    /** A failed refresh leaves the cache untouched: stale checkpoints beat none. */
    /**
     * The checkpoint list is *replaced*, not merged.
     *
     * Upserting alone only ever adds. A post the office retires, or one that belongs to a campus
     * this guard has left, stays on the phone forever: it keeps resolving on a scan, and it keeps
     * padding the round — a guard was being told "1 of 15 posts scanned" when the campus has six.
     *
     * A failed refresh leaves the previous list alone. A stale checkpoint beats no checkpoint: a
     * guard who cannot resolve a scan cannot record their attendance at all.
     */
    override suspend fun refresh(): ApiResult<Unit> =
        errors.call { api.checkpoints().data }
            .also { result ->
                if (result is ApiResult.Success) {
                    val now = clock.nowMillis()
                    dao.replaceAll(result.value.map { it.toEntity(now) })
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
    private val evaluations: EvaluationJson,
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
    private val clock: Clock,
) : AttendanceRepository {

    /**
     * The guard whose records these are.
     *
     * Every read below is scoped to them. A handset is handed on between shifts, and the records of
     * the guard who had it last are still in the table — as they should be, they are evidence — but
     * they are not this guard's history, this guard's round, or this guard's Time In.
     *
     * `-1` when nobody is signed in: an id no guard has, so the queries return nothing rather than
     * everything.
     */
    private fun currentUserId(): Flow<Long> = profiles.observe().map { it?.id ?: NO_USER }

    override fun observeUnsyncedCount(): Flow<Int> =
        currentUserId().flatMapLatest { dao.observeUnsyncedCount(it) }

    override fun observeOtherAccountUnsyncedCount(): Flow<Int> =
        currentUserId().flatMapLatest { dao.observeOtherAccountUnsyncedCount(it) }

    override fun observeHistory(limit: Int): Flow<List<AttendanceRecord>> =
        currentUserId().flatMapLatest { userId ->
            dao.observePage(userId, limit).map { entities -> entities.map { it.toDomain() } }
        }

    override fun observeRecord(id: String): Flow<AttendanceRecord?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun refreshHistory(): ApiResult<Int> {
        // Nothing to attribute the records to, and every read is scoped by guard id, so downloading
        // now would file them under -1 and show them to nobody.
        if (userId() == NO_USER) return ApiResult.Success(0)

        var page = 1
        var restored = 0

        while (page <= MAX_HISTORY_PAGES) {
            val result = errors.call { api.history(page = page, perPage = HISTORY_PAGE_SIZE) }
            if (result !is ApiResult.Success) return result.map { 0 }

            val now = clock.nowMillis()
            // mapNotNull: a record this build cannot read is skipped, not fatal. Losing one row to a
            // format change must not cost the guard the other several hundred.
            val rows = dao.insertDownloaded(result.value.data.mapNotNull { it.toEntity(now) })
            // Room's IGNORE returns -1 for a row already present. Counting only the rest is what
            // makes the number reported to the guard mean "new to this phone" rather than "seen".
            restored += rows.count { it != -1L }

            val meta = result.value.meta
            if (page * meta.perPage >= meta.total) break
            page++
        }
        return ApiResult.Success(restored)
    }

    override suspend fun retry(id: String) {
        // Only re-queue if the record was actually in a retriable-by-hand state; requesting a
        // sync for a record that did not move would just spin the worker for nothing.
        if (dao.requeue(id, clock.nowMillis()) > 0) syncScheduler.requestSync()
    }

    override fun observeStuckCount(): Flow<Int> =
        currentUserId().flatMapLatest { dao.observeStuckCount(it) }

    override fun observeRejectedCount(): Flow<Int> =
        currentUserId().flatMapLatest { dao.observeRejectedCount(it) }

    /**
     * The selfies go first, then the rows.
     *
     * In that order deliberately. A file deleted whose row survives is a record pointing at a
     * photograph that is gone — it would still be listed, still be retried, and fail on an upload
     * that can never find its image. A row deleted whose file survives is only wasted bytes in the
     * cache. If this is interrupted halfway, the second is the failure to have.
     */
    override suspend fun discardRejected(): Int {
        val userId = userId()
        if (userId == NO_USER) return 0

        dao.rejected(userId).forEach { record ->
            runCatching { File(record.selfiePath).delete() }
        }

        return dao.deleteRejected(userId)
    }

    /**
     * Both directions, because "Sync now" is not a word that means "upload".
     *
     * Unlike [retry], this always drains — even when nothing was stuck. The queue may hold records
     * that are merely PENDING behind a network that has just come back, and the guard tapping the
     * button is entitled to see them go.
     *
     * And it pulls. This used to push only, which was defensible in the code and indefensible on
     * the screen: a guard setting up a replacement handset taps the one button labelled Sync,
     * watches nothing happen, and concludes their attendance is gone. The download did exist — it
     * was reachable only by opening Home, which is not a thing anybody would guess.
     *
     * The pull runs after the push is kicked off, not before. A record that exists only on this
     * phone is the one irreplaceable thing here; getting it moving matters more than filling in
     * history, and the two do not wait on each other.
     */
    override suspend fun syncNow(): SyncOutcome {
        val requeued = dao.requeueAll(clock.nowMillis())
        syncScheduler.syncNow()

        // A failed pull is not a failed sync. Offline, the upload half still happened and is still
        // worth reporting; the records this would have fetched are safe on the server either way.
        val pulled = refreshHistory()

        return SyncOutcome(
            requeued = requeued,
            downloaded = (pulled as? ApiResult.Success)?.value ?: 0,
            reachedServer = pulled is ApiResult.Success,
        )
    }

    override fun observeInRange(fromMillis: Long, toMillis: Long): Flow<List<AttendanceRecord>> =
        currentUserId().flatMapLatest { userId ->
            dao.observeInRange(userId, fromMillis, toMillis)
                .map { entities -> entities.map { it.toDomain() } }
        }

    /*
     * Counted over the shift, not over the day.
     *
     * These were bounded midnight to midnight, which is right for a shift that begins and ends on
     * one date and wrong for every night shift. At 00:00 a rover's round reset to zero, the Time
     * Out they had not yet recorded looked un-recorded in a new day, and the post they clocked on
     * at was forgotten — mid-shift, with no way for the guard to tell what had happened.
     *
     * The caller passes the window because the caller is the one that knows which shift is running;
     * see `DutyAssignment.attendanceWindow()`, which falls back to the local day for a duty the
     * office filed without hours.
     */
    override suspend fun checkpointVisitsIn(window: ShiftWindow): Map<Long, Int> =
        dao.checkpointVisitCountsBetween(userId(), window.start, window.end)
            .associate { it.checkpointId to it.visits }

    override suspend fun lastVisitedCheckpointIn(window: ShiftWindow): Long? =
        dao.lastVisitedCheckpointBetween(userId(), window.start, window.end)

    override suspend fun hasTimedOutIn(window: ShiftWindow): Boolean =
        dao.firstTimeOutBetween(userId(), window.start, window.end) != null

    private suspend fun userId(): Long = profiles.observe().first()?.id ?: NO_USER

    override suspend fun submit(id: String, draft: AttendanceDraft) {
        val now = clock.nowMillis()
        val userId = userId()
        // A record stamped with NO_USER is orphaned: every history and report query is scoped to the
        // signed-in guard's id, so a -1 record is written to the table and then shown to no one. Refuse
        // rather than write it — the guard sees an error and can retry, instead of a capture that
        // vanishes silently. In practice this only fires if the profile flow has not emitted yet.
        check(userId != NO_USER) {
            "Could not tell who is signed in. Reopen the app and try again — your photo is not lost."
        }
        // Insert first, then enqueue. If the insert throws the record is not committed and no
        // sync is scheduled; if enqueue somehow fails the record is still safely PENDING and
        // the periodic drain (T-24) picks it up.
        dao.insert(
            AttendanceEntity(
                id = id,
                userId = userId,
                checkpointId = draft.checkpointId,
                checkpointCode = draft.checkpointCode,
                attendanceType = when (draft.type) {
                    AttendanceType.TIME_IN -> EntityAttendanceType.TIME_IN
                    AttendanceType.TIME_OUT -> EntityAttendanceType.TIME_OUT
                    AttendanceType.CHECKPOINT -> EntityAttendanceType.CHECKPOINT
                },
                selfiePath = draft.selfiePath,
                capturedAt = draft.capturedAtMillis,
                latitude = draft.latitude,
                longitude = draft.longitude,
                accuracy = draft.accuracyMetres,
                // No longer a gate on the capture; kept for records written under the old rule.
                dutiesAcknowledged = false,
                dutiesVersionId = draft.dutiesVersionId,
                // Serialised onto the record so the answers survive on the phone until the upload
                // lands — which may be hours, and across a process death.
                evaluationsJson = draft.evaluations
                    .takeIf { it.isNotEmpty() }
                    ?.let(evaluations::encode),
                deviceId = null,
                syncStatus = SyncStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            )
        )
        syncScheduler.requestSync()
    }
}

/** An id no guard has. Scopes a query to nobody rather than to everybody when nobody is signed in. */
private const val NO_USER = -1L

private const val HISTORY_PAGE_SIZE = 100

/**
 * A ceiling on the backfill, not a page budget for a working device: the loop stops as soon as the
 * server says there are no more records, so a guard with two months of history costs one or two
 * requests. It exists so that a guard with years of them cannot turn a first login into a hundred
 * round trips on a phone tethered to a perimeter post.
 */
private const val MAX_HISTORY_PAGES = 10
