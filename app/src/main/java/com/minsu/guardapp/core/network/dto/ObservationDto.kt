package com.minsu.guardapp.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The server's copy of an uploaded observation.
 *
 * Only [id] is required, because it is the only thing the app acts on — it marks the local row
 * synced. Everything else is defaulted, for the reason spelled out on [AttendanceDto]: a field this
 * client ignores must never be able to turn a stored 201 into a parse failure.
 */
@JsonClass(generateAdapter = true)
data class ObservationDto(
    @Json(name = "id") val id: Long,
    @Json(name = "client_uuid") val clientUuid: String? = null,
    @Json(name = "status") val status: String? = null,
)
