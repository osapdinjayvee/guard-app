package com.minsu.guardapp.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * A published PDF, as served by `GET /api/documents/{identifier}`.
 *
 * The endpoint answers with the fields both nested under `data` and repeated at the top level.
 * Only the envelope is read here — [com.minsu.guardapp.core.network.dto.Envelope] handles it, and
 * a second copy of the same three fields is not worth a second shape to parse it with.
 */
@JsonClass(generateAdapter = true)
data class DocumentDto(
    @Json(name = "type") val type: String? = null,
    @Json(name = "url") val url: String,
    @Json(name = "filename") val filename: String? = null,
)

/** One yes/no question of the self-evaluation, as served by `GET /api/evaluation-questions`. */
@JsonClass(generateAdapter = true)
data class EvaluationQuestionDto(
    @Json(name = "id") val id: Long,
    @Json(name = "question") val question: String,
    /**
     * `time_in`, `time_out` or `both` — which end of the shift the office asks this at.
     *
     * Defaulted rather than required. The whole set is downloaded in one call and filtered on the
     * device, so a server that has not been updated to send the field still yields a working
     * post-shift evaluation instead of an empty one.
     */
    @Json(name = "type") val type: String? = null,
    /**
     * `stationed`, `roving` or `both` — which duty the office asks this of.
     *
     * A stationed guard is not asked about the round, and a rover is not asked about the post they
     * never leave. Defaulted like [type]: a server that predates the field asks everyone the same
     * questions, which is what it did before the field existed.
     */
    @Json(name = "target_guard_type") val targetGuardType: String? = null,
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
