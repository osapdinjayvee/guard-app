package com.minsu.guardapp.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UserDto(
    @Json(name = "id") val id: Long,
    @Json(name = "name") val name: String,
    @Json(name = "username") val username: String,
    @Json(name = "email") val email: String? = null,
    @Json(name = "role") val role: String? = null,
    @Json(name = "status") val status: String,
)

@JsonClass(generateAdapter = true)
data class ProfileDto(
    @Json(name = "user") val user: UserDto,
)

@JsonClass(generateAdapter = true)
data class DutyDto(
    /** Submitted back as `duties_version_id` so the record says *which* duties were acknowledged. */
    @Json(name = "id") val id: Long,
    @Json(name = "title") val title: String,
    @Json(name = "content") val content: String,
    @Json(name = "active") val active: Boolean,
    @Json(name = "effective_date") val effectiveDate: String? = null,
)

@JsonClass(generateAdapter = true)
data class AnnouncementDto(
    @Json(name = "id") val id: Long,
    @Json(name = "title") val title: String,
    @Json(name = "content") val content: String,
)

@JsonClass(generateAdapter = true)
data class MobileSettingsDto(
    // Every field is optional; the client falls back to these defaults. Adding a key
    // server-side is therefore not a breaking change.
    /** The server's geofence. Enforced on the client too, so a wasted capture is never taken. */
    @Json(name = "geofence_radius_m") val geofenceRadiusM: Float = 100f,
    @Json(name = "gps_accuracy_threshold_m") val gpsAccuracyThresholdM: Float = 50f,
    @Json(name = "gps_failure_policy") val gpsFailurePolicy: String = "block",
    @Json(name = "gps_timeout_seconds") val gpsTimeoutSeconds: Int = 15,
    @Json(name = "image_quality") val imageQuality: Int = 80,
    @Json(name = "image_max_dimension_px") val imageMaxDimensionPx: Int = 1600,
    @Json(name = "time_in_early_minutes") val timeInEarlyMinutes: Int = 15,
    @Json(name = "min_visits_per_checkpoint") val minVisitsPerCheckpoint: Int = 2,
    @Json(name = "maintenance_message") val maintenanceMessage: String? = null,
)

@JsonClass(generateAdapter = true)
data class AttendanceDto(
    @Json(name = "id") val id: Long,
    @Json(name = "client_uuid") val clientUuid: String,
    @Json(name = "user_id") val userId: Long,
    @Json(name = "qr_checkpoint_id") val checkpointId: Long,
    @Json(name = "checkpoint_code") val checkpointCode: String? = null,
    @Json(name = "attendance_type") val attendanceType: String,
    @Json(name = "selfie_url") val selfieUrl: String,
    /** Device clock at shutter. May be hours before [receivedAt]. */
    @Json(name = "captured_at") val capturedAt: String,
    /** Server clock when the upload landed. Authoritative for audit. */
    @Json(name = "received_at") val receivedAt: String,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "accuracy") val accuracy: Float? = null,
    /*
     * Defaulted, not required — and the reason is worth keeping.
     *
     * The server dropped `duties_acknowledged` from this response. Declared as a required Boolean,
     * its absence made Moshi throw while reading a body the server had already answered 201 to:
     * the attendance was safely stored, and every guard saw the record marked Rejected. A field
     * this client does not act on must never be able to do that.
     *
     * The rule for everything below: if the app does not make a decision from it, it gets a
     * default.
     */
    @Json(name = "duties_acknowledged") val dutiesAcknowledged: Boolean = false,
    @Json(name = "duties_version_id") val dutiesVersionId: Long? = null,
    @Json(name = "device_id") val deviceId: String? = null,
)
