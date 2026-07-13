package com.minsu.guardapp.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * The guard's own duty roster, as served by `GET /api/schedule`.
 *
 * There is no checkpoint anywhere in here, deliberately. A stationed guard's post is not assigned in
 * advance — it is wherever they scan in — and the rule that follows, Time Out where you Timed In, is
 * derived from the guard's own attendance rather than from anything the office filled in.
 */
@JsonClass(generateAdapter = true)
data class ScheduleDto(
    /**
     * False when nobody has linked this login to a guard on the roster.
     *
     * Distinct from "no shift today", which looks identical to a guard and means something entirely
     * different: one is a rest day, the other is an admin error that will keep them from working.
     */
    @Json(name = "linked") val linked: Boolean = false,
    @Json(name = "today") val today: ScheduleEntryDto? = null,
    @Json(name = "entries") val entries: List<ScheduleEntryDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ScheduleEntryDto(
    @Json(name = "id") val id: Long,
    @Json(name = "date") val date: String,
    /** `SG` or `RG`. The code, not the name: the client branches on it. */
    @Json(name = "duty_type") val dutyType: String?,
    @Json(name = "duty_name") val dutyName: String? = null,
    @Json(name = "starts_at") val startsAt: String? = null,
    @Json(name = "ends_at") val endsAt: String? = null,
    @Json(name = "total_hours") val totalHours: Float = 0f,
)
