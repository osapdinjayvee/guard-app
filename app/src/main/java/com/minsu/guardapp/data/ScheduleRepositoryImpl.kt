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
import com.minsu.guardapp.domain.model.ShiftWindow
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.repository.ScheduleRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ScheduleRepository {

    /**
     * The grace comes from the server's settings, so the app and the server agree about when a
     * shift stops being closeable. If they disagree the app offers a Time Out the server refuses,
     * which is the worst place for the two to differ.
     */
    override fun observeCurrentDuty(): Flow<DutyAssignment?> =
        combine(dao.observeForDates(dates()), settings.observe()) { rows, config ->
            rows.currentOrNext(clock.nowMillis(), todayDate(), config.shiftCloseGraceMinutes)
        }

    override suspend fun currentDuty(): DutyAssignment? =
        dao.forDates(dates())
            .currentOrNext(clock.nowMillis(), todayDate(), settings.current().shiftCloseGraceMinutes)

    /** Yesterday and today: the only two dates an entry covering *now* can be filed against. */
    private fun dates(): List<String> = listOf(yesterdayDate(), todayDate())

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
     * The checkpoint this guard opened the shift at, if they have — read from the local database,
     * so it holds with no signal.
     *
     * This is a stationed guard's post. Nobody assigns it; the first Time In of the *shift* defines
     * it, and the app refuses to let them close the shift anywhere else. Bounded by the shift and
     * not the day, or a night guard's post is forgotten the moment the date rolls over.
     */
    override suspend fun postTimedInAt(window: ShiftWindow): Long? {
        // This guard's own Time In. The handset may still hold the last guard's, and treating theirs
        // as this one's would pin a stationed guard to a post they never stood at.
        val userId = profiles.observe().first()?.id ?: return null

        return attendance.firstTimeInBetween(userId, window.start, window.end)?.checkpointId
    }

    private fun todayDate(): String = clock.nowMillis().asDate()

    /** The date in the *guard's* timezone. A 23:50 capture belongs to the day they think it is. */
    private fun yesterdayDate(): String = Calendar.getInstance()
        .apply {
            timeInMillis = clock.nowMillis()
            add(Calendar.DAY_OF_MONTH, -1)
        }
        .timeInMillis
        .asDate()

    private fun Long.asDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(this))
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
