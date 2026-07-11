package com.minsu.guardapp.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Wire shape of a checkpoint from `GET /api/checkpoints`.
 *
 * Provisional: the API contract is not yet ratified and the backend repo does not exist.
 * Field names follow the Laravel snake_case convention described in the backend PRD §8.2.
 * See `.docs/Implementation_Plan.md` §5 — T-8 freezes this.
 */
@JsonClass(generateAdapter = true)
data class CheckpointDto(
    @Json(name = "id") val id: Long,
    @Json(name = "code") val code: String,
    /** Cached alongside the code, because a QR sticker may carry either. */
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "name") val name: String,
    @Json(name = "description") val description: String? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "status") val status: String,
)
