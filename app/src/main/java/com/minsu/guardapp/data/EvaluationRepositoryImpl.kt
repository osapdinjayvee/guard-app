package com.minsu.guardapp.data

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.EvaluationQuestionDao
import com.minsu.guardapp.core.database.EvaluationQuestionEntity
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.map
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.EvaluationQuestion
import com.minsu.guardapp.domain.model.EvaluationTiming
import com.minsu.guardapp.domain.repository.EvaluationRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultEvaluationRepository @Inject constructor(
    private val dao: EvaluationQuestionDao,
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
    private val clock: Clock,
) : EvaluationRepository {

    override suspend fun questions(type: AttendanceType): List<EvaluationQuestion> =
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
                )
            }
            .filter { it.timing.appliesTo(type) }

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
    override suspend fun refresh(): ApiResult<Unit> =
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
                                sortOrder = it.sortOrder,
                                updatedAt = now,
                            )
                        }
                    )
                }
            }
            .map { }
}
