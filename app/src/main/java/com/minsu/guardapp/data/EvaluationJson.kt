package com.minsu.guardapp.data

import com.minsu.guardapp.core.network.dto.EvaluationAnswerDto
import com.minsu.guardapp.domain.model.EvaluationAnswer
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The post-shift answers, on the wire and in the database.
 *
 * One encoder for both, deliberately: the string stored on the attendance row is *the same string*
 * that is later posted to the server. The answers are captured on a phone with no signal and sent
 * hours later, so anything that re-derived the payload at upload time would be a second chance to
 * disagree with what the guard actually said.
 *
 * JSON in a single field because the attendance upload is multipart — it carries a selfie — and
 * multipart has no notion of a nested array.
 */
@Singleton
class EvaluationJson @Inject constructor(moshi: Moshi) {

    private val adapter = moshi.adapter<List<EvaluationAnswerDto>>(
        Types.newParameterizedType(List::class.java, EvaluationAnswerDto::class.java)
    )

    fun encode(answers: List<EvaluationAnswer>): String =
        adapter.toJson(answers.map { EvaluationAnswerDto(it.questionId, it.answer) })

    /** Malformed JSON yields no answers rather than an exception: a bad row must not stall the queue. */
    fun decode(json: String?): List<EvaluationAnswer> {
        if (json.isNullOrBlank()) return emptyList()

        return runCatching { adapter.fromJson(json) }
            .getOrNull()
            .orEmpty()
            .map { EvaluationAnswer(it.questionId, it.answer) }
    }
}
