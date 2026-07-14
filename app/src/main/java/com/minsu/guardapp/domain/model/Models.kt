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
    val geofenceRadiusMetres: Float = 100f,
    val gpsAccuracyThresholdMetres: Float = 50f,
    val gpsFailurePolicy: GpsFailurePolicy = GpsFailurePolicy.BLOCK,
    val gpsTimeoutSeconds: Int = 15,
    val imageQuality: Int = 80,
    val imageMaxDimensionPx: Int = 1600,
    /** How early a guard may open a shift. Only earliness is capped; a late Time In still records. */
    val timeInEarlyMinutes: Int = 15,
    /** Checkpoint visits a roving guard must record before they can close the shift. */
    val minCheckpointVisits: Int = 2,
    val maintenanceMessage: String? = null,
)

/**
 * What a scan records.
 *
 * CHECKPOINT is a patrol visit, and only a roving guard makes one — a stationed guard is at a single
 * post for the whole shift, so there is nothing for them to visit.
 */
enum class AttendanceType { TIME_IN, TIME_OUT, CHECKPOINT }

/** SG or RG. What the guard is rostered to do on a given day. */
enum class DutyType {
    /** One post for the shift: Time In and Time Out, at the checkpoint they scanned in at. */
    STATIONED,

    /** A round: Time In to start, a visit at each checkpoint, Time Out to end. */
    ROVING,
    ;

    companion object {
        /** The server speaks in codes. Anything unrecognised is not guessed at. */
        fun fromCode(code: String?): DutyType? = when (code?.uppercase()) {
            "SG" -> STATIONED
            "RG" -> ROVING
            else -> null
        }
    }
}

/** One day on the roster. */
data class DutyAssignment(
    val date: String,
    val dutyType: DutyType,
    val dutyName: String?,
    val startsAt: String?,
    val endsAt: String?,
    val totalHours: Float,
) {
    /** The types this guard may record today. A stationed guard never sees a patrol visit. */
    val allowedTypes: List<AttendanceType>
        get() = when (dutyType) {
            DutyType.STATIONED -> listOf(AttendanceType.TIME_IN, AttendanceType.TIME_OUT)
            DutyType.ROVING -> listOf(
                AttendanceType.TIME_IN,
                AttendanceType.CHECKPOINT,
                AttendanceType.TIME_OUT,
            )
        }
}

/** One yes/no question put to a guard at the end of their shift. */
data class EvaluationQuestion(
    val id: Long,
    val question: String,
    val sortOrder: Int,
)

/** The guard's answer to one of them, carried with the Time Out it describes. */
data class EvaluationAnswer(
    val questionId: Long,
    val answer: Boolean,
)

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
    /**
     * The post-shift self-evaluation. Non-empty only on a Time Out — an evaluation describes a shift
     * that has ended, and one attached to a Time In would be a claim about a shift that has not
     * happened yet. The server rejects it on anything else.
     */
    val evaluations: List<EvaluationAnswer> = emptyList(),
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
