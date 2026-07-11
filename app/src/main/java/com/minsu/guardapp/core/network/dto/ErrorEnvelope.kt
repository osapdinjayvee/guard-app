package com.minsu.guardapp.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** The single error shape used by every non-2xx response, per `.docs/api/openapi.yaml`. */
@JsonClass(generateAdapter = true)
data class ErrorEnvelope(
    @Json(name = "error") val error: ErrorBody,
)

@JsonClass(generateAdapter = true)
data class ErrorBody(
    /** Stable and machine-readable. The client switches on this, never on [message]. */
    @Json(name = "code") val code: String,
    /** Human-readable; safe to show to a guard. */
    @Json(name = "message") val message: String,
    /** Field-keyed validation messages, present when `code = validation_failed`. */
    @Json(name = "details") val details: Map<String, List<String>>? = null,
)
