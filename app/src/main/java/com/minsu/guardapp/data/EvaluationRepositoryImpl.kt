package com.minsu.guardapp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.EvaluationQuestionDao
import com.minsu.guardapp.core.database.EvaluationQuestionEntity
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.map
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.model.EvaluationQuestion
import com.minsu.guardapp.domain.model.EvaluationTiming
import com.minsu.guardapp.domain.model.GuardTarget
import com.minsu.guardapp.domain.repository.EvaluationRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultEvaluationRepository @Inject constructor(
    private val dao: EvaluationQuestionDao,
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) : EvaluationRepository {

    override suspend fun questions(type: AttendanceType, duty: DutyType?): List<EvaluationQuestion> =
        dao.all()
            .map {
                EvaluationQuestion(
                    id = it.id,
                    question = it.question,
                    sortOrder = it.sortOrder,
                    timing = runCatching { EvaluationTiming.valueOf(it.timing) }
                        // A row written by a build that knew a timing this one does not. Asking it
                        // at the end of the shift is where every question lived before timings
                        // existed, and is the safer of the two places to guess.
                        .getOrDefault(EvaluationTiming.TIME_OUT),
                    asksOf = GuardTarget.fromWire(it.targetGuardType),
                )
            }
            .filter { it.timing.appliesTo(type) && it.asksOf.appliesTo(duty) }

    /**
     * Whether the cached set belongs to a different duty than the one being worked.
     *
     * The server filters by the duty it sees at request time, so the set on the phone is only
     * correct for the duty it was fetched under. A guard stationed on Monday and roving on Tuesday
     * would otherwise answer Monday's questions on Tuesday — and the server, which checks the set
     * again on submission, would refuse the Time Out with a 422.
     *
     * Unknown means stale. A phone that has never recorded which duty it fetched for is a phone
     * upgrading into this build, and one wasted refresh is cheaper than one refused Time Out.
     */
    override suspend fun isStaleFor(duty: DutyType?): Boolean =
        duty != null && dataStore.data.first()[FETCHED_FOR_DUTY] != duty.name

    /**
     * Replaced wholesale, not merged.
     *
     * A question the office retired has to stop being asked. Merging would leave it on the phones
     * that already had it and not on the ones that did not, and two guards would then give different
     * evaluations of the same kind of shift — which is precisely the thing a standard set of
     * questions exists to prevent.
     *
     * A failed refresh leaves the previous set alone: a guard closing a shift with yesterday's
     * questions is fine, and one who cannot close it at all is not.
     */
    override suspend fun refresh(duty: DutyType?): ApiResult<Unit> =
        errors.call { api.evaluationQuestions().data }
            .also { result ->
                if (result is ApiResult.Success) {
                    val now = clock.nowMillis()
                    dao.clear()
                    dao.upsertAll(
                        result.value.map {
                            EvaluationQuestionEntity(
                                id = it.id,
                                question = it.question,
                                timing = EvaluationTiming.fromWire(it.type).name,
                                targetGuardType = GuardTarget.fromWire(it.targetGuardType).name,
                                sortOrder = it.sortOrder,
                                updatedAt = now,
                            )
                        }
                    )

                    // Which duty this set answers for. The server filtered it by the duty it saw
                    // just now, so the set and the duty are one fact and are recorded together.
                    dataStore.edit { prefs ->
                        duty?.let { prefs[FETCHED_FOR_DUTY] = it.name } ?: prefs.remove(FETCHED_FOR_DUTY)
                    }
                }
            }
            .map { }

    private companion object {
        val FETCHED_FOR_DUTY = stringPreferencesKey("evaluation_questions_fetched_for_duty")
    }
}
