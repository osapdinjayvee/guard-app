package com.minsu.guardapp.domain.model

/** Framework-free models. No Room, no Retrofit, no Android types. */

data class Checkpoint(
    val id: Long,
    /** The value encoded in the QR image. */
    val code: String,
    val name: String,
    val isActive: Boolean,
    val latitude: Double?,
    val longitude: Double?,
)

/**
 * The outcome of scanning a QR code.
 *
 * [Disabled] is distinct from [Unknown] on purpose: a guard scanning a retired checkpoint
 * deserves "this checkpoint is no longer in use", not "unrecognised code". That is why the
 * cache stores disabled checkpoints rather than filtering them out.
 */
sealed interface CheckpointResolution {
    data class Resolved(val checkpoint: Checkpoint) : CheckpointResolution

    /** A retired checkpoint. Named apart from [Unknown] so the guard knows to try another door. */
    data class Disabled(val checkpoint: Checkpoint) : CheckpointResolution

    /** The server looked and there is no such checkpoint. A wrong sticker, or a stale one. */
    data class Unknown(val code: String) : CheckpointResolution

    /**
     * Not in the cache, and the server could not be reached to ask.
     *
     * This is emphatically not [Unknown]. Saying "this is not a checkpoint" when the truth is "I
     * could not check" sends a guard hunting for another door, or makes them think the QR on the
     * wall is broken, when in fact the only problem is that their phone has no signal and has never
     * downloaded the checkpoint list. The two must never be worded the same way.
     */
    data class Unverifiable(val code: String) : CheckpointResolution
}

data class Duty(
    /** Submitted with the attendance record, so an acknowledgement says *which* revision. */
    val id: Long,
    val title: String,
    val content: String,
)

data class Announcement(
    val id: Long,
    val title: String,
    val content: String,
)

data class GuardProfile(
    val id: Long,
    val name: String,
    val username: String,
)

enum class GpsFailurePolicy {
    /** No acceptable fix means the guard cannot submit. */
    BLOCK,

    /** Submission proceeds and the record stores null coordinates. */
    ALLOW;

    companion object {
        /**
         * Unknown values fail safe. An attendance record without a location is weak evidence,
         * and a typo in a server config should not silently start accepting them.
         */
        fun parse(value: String?): GpsFailurePolicy =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: BLOCK
    }
}

/**
 * Server-controlled client behaviour. Never hardcode these; read them from the repository.
 * Defaults apply only until `GET /api/settings` has succeeded once.
 */
data class AppSettings(
    val gpsAccuracyThresholdMetres: Float = 50f,
    val gpsFailurePolicy: GpsFailurePolicy = GpsFailurePolicy.BLOCK,
    val gpsTimeoutSeconds: Int = 15,
    val imageQuality: Int = 80,
    val imageMaxDimensionPx: Int = 1600,
    val maintenanceMessage: String? = null,
)

enum class AttendanceType { TIME_IN, TIME_OUT }

enum class SyncState { PENDING, SYNCING, SYNCED, FAILED, REJECTED }

/**
 * A completed capture, ready to become a durable attendance record. Everything here is settled
 * on-device before submission; the server is never consulted first.
 */
data class AttendanceDraft(
    val checkpointId: Long,
    val checkpointCode: String,
    val type: AttendanceType,
    val selfiePath: String,
    val capturedAtMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMetres: Float?,
    val dutiesVersionId: Long?,
)

data class AttendanceRecord(
    val id: String,
    val checkpointCode: String,
    val type: AttendanceType,
    val capturedAt: Long,
    val selfiePath: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMetres: Float?,
    val syncState: SyncState,
    val lastError: String?,
)
