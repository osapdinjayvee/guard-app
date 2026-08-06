package com.minsu.guardapp.data

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.AttendanceDao
import com.minsu.guardapp.core.database.ScheduleDao
import com.minsu.guardapp.core.database.ScheduleEntity
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.map
import com.minsu.guardapp.core.security.RosterPreferences
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultScheduleRepository @Inject constructor(
    private val dao: ScheduleDao,
    private val attendance: AttendanceDao,
    private val roster: RosterPreferences,
    private val profiles: ProfileRepository,
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
    private val clock: Clock,
) : ScheduleRepository {

    override fun observeToday(): Flow<DutyAssignment?> =
        dao.observeForDate(todayDate()).map { rows -> rows.currentOrNext(clock.nowMillis()) }

    override suspend fun today(): DutyAssignment? =
        dao.forDate(todayDate()).currentOrNext(clock.nowMillis())

    override fun observeAll(): Flow<List<DutyAssignment>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    override val isLinked: Flow<Boolean> = roster.linked

    /**
     * The roster is *replaced*, not merged.
     *
     * A shift the office cancelled has to disappear from the phone. Upserting alone would leave it
     * in the cache forever, and the scanner would go on offering a guard the buttons for a shift
     * that no longer exists. A failed refresh leaves the previous roster untouched — a stale duty
     * beats none, exactly as with the checkpoints.
     */
    override suspend fun refresh(): ApiResult<Unit> =
        errors.call { api.schedule().data }
            .also { result ->
                if (result is ApiResult.Success) {
                    val schedule = result.value
                    roster.setLinked(schedule.linked)

                    val now = clock.nowMillis()
                    dao.clear()
                    dao.upsertAll(
                        schedule.entries.mapNotNull { entry ->
                            // A duty code we do not recognise is dropped rather than guessed at.
                            // Guessing would offer the guard the wrong buttons, which is worse than
                            // offering none.
                            DutyType.fromCode(entry.dutyType) ?: return@mapNotNull null

                            ScheduleEntity(
                                date = entry.date,
                                dutyType = entry.dutyType.orEmpty(),
                                dutyName = entry.dutyName,
                                startsAt = entry.startsAt,
                                endsAt = entry.endsAt,
                                totalHours = entry.totalHours,
                                updatedAt = now,
                            )
                        }
                    )
                }
            }
            .map { }

    /**
     * The checkpoint this guard timed in at today, if they have — read from the local database, so
     * it holds with no signal.
     *
     * This is a stationed guard's post. Nobody assigns it; the first Time In of the day defines it,
     * and the app refuses to let them close the shift anywhere else.
     */
    override suspend fun postTimedInAtToday(): Long? {
        val (from, to) = todayBounds()
        // This guard's own Time In. The handset may still hold the last guard's, and treating theirs
        // as this one's would pin a stationed guard to a post they never stood at.
        val userId = profiles.observe().first()?.id ?: return null

        return attendance.firstTimeInBetween(userId, from, to)?.checkpointId
    }

    private fun todayDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(clock.nowMillis()))

    /**
     * Midnight to midnight in the *guard's* timezone, not UTC's.
     *
     * A 23:50 Time In belongs to the day the guard thinks it is. Bounding the day in UTC would put
     * a late-evening capture in Manila into tomorrow, and the shift would appear to have no Time In
     * at all.
     */
    private fun todayBounds(): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            timeInMillis = clock.nowMillis()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val from = start.timeInMillis
        start.add(Calendar.DAY_OF_MONTH, 1)
        return from to start.timeInMillis
    }
}

internal fun ScheduleEntity.toDomain(): DutyAssignment? {
    val type = DutyType.fromCode(dutyType) ?: return null
    return DutyAssignment(
        date = date,
        dutyType = type,
        dutyName = dutyName,
        startsAt = startsAt,
        endsAt = endsAt,
        totalHours = totalHours,
    )
}
