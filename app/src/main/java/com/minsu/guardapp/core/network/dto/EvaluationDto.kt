package com.minsu.guardapp.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** One yes/no question of the post-shift self-evaluation, as served by `GET /api/evaluation-questions`. */
@JsonClass(generateAdapter = true)
data class EvaluationQuestionDto(
    @Json(name = "id") val id: Long,
    @Json(name = "question") val question: String,
    @Json(name = "sort_order") val sortOrder: Int = 0,
)

/**
 * One answer, uploaded with the Time Out it describes.
 *
 * Sent as a JSON array in a single multipart field: the attendance carries a selfie, so the upload
 * is multipart, and multipart has no notion of a nested array.
 */
@JsonClass(generateAdapter = true)
data class EvaluationAnswerDto(
    @Json(name = "question_id") val questionId: Long,
    @Json(name = "answer") val answer: Boolean,
)
