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
    /** False for a patrol-only post: walked past on a round, never a place a shift begins or ends. */
    val allowsTimeInOut: Boolean = true,
)

/**
 * The posts a round is made of.
 *
 * A checkpoint where shifts start and end is not a stop on the patrol — the guard's presence there
 * is already recorded, by the Time In and the Time Out. Counting it again as somewhere to visit
 * asks them to walk to the door they clocked on at, and puts a post on the round list that is
 * really the shift's bookends under another name.
 *
 * One definition, used by everything that speaks about the round: the scan card's outstanding
 * list, the Home remaining-stops card, and the Round screen. They must never disagree about what
 * the round is.
 */
fun List<Checkpoint>.roundPosts(): List<Checkpoint> = filterNot { it.allowsTimeInOut }

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
    /**
     * How long after a shift ends it is still that shift being closed.
     *
     * A guard does not stop working at the instant the roster says: they hand over, wait for their
     * relief, walk back from the far end of the campus. Without this the Time Out is judged against
     * whatever comes next — for a night guard, usually a day off — and refused.
     */
    val shiftCloseGraceMinutes: Int = 120,
    /** Visits *each* post must receive before a roving guard can close the shift. Two full sweeps. */
    val minVisitsPerCheckpoint: Int = 2,
    val maintenanceMessage: String? = null,
)

/**
 * What a scan records.
 *
 * CHECKPOINT is a patrol visit, and only a roving guard makes one — a stationed guard is at a single
 * post for the whole shift, so there is nothing for them to visit.
 */
enum class AttendanceType { TIME_IN, TIME_OUT, CHECKPOINT }

/** What the guard is scheduled to do on a given day. */
enum class DutyType {
    /** One post for the shift: Time In and Time Out, at the checkpoint they scanned in at. */
    STATIONED,

    /** A round: Time In to start, a visit at each checkpoint, Time Out to end. */
    ROVING,

    /**
     * A rest day, and a real answer rather than the absence of one.
     *
     * The office files these explicitly — every guard not scheduled to work a day gets one. The app
     * used to discard them, which left a rest day indistinguishable from a schedule nobody had
     * filled in: both came out as "not on duty", and a guard could not tell whether they were off
     * or whether the office had forgotten them.
     */
    OFF,
    ;

    companion object {
        /** The server speaks in codes. Anything unrecognised is not guessed at. */
        fun fromCode(code: String?): DutyType? = when (code?.uppercase()) {
            "SG" -> STATIONED
            "RG" -> ROVING
            "OFF" -> OFF
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
    /**
     * Unique within a roster, unlike the date.
     *
     * A day can hold more than one shift, so keying a list on the date alone is a duplicate key —
     * which `LazyColumn` does not tolerate: it throws, and the Schedule screen dies on open. The
     * date used to be safe because the roster cache could physically hold only one entry per day.
     * That was the bug, and this is the other end of fixing it.
     */
    val rowKey: String get() = "$date#${startsAt.orEmpty()}#${endsAt.orEmpty()}"

    val allowedTypes: List<AttendanceType>
        get() = when (dutyType) {
            DutyType.STATIONED -> listOf(AttendanceType.TIME_IN, AttendanceType.TIME_OUT)
            DutyType.ROVING -> listOf(
                AttendanceType.TIME_IN,
                AttendanceType.CHECKPOINT,
                AttendanceType.TIME_OUT,
            )
            // Nothing is recorded against a rest day. Callers are expected to notice this before
            // showing a card with no buttons on it.
            DutyType.OFF -> emptyList()
        }

    /** A day the guard is not working. Not the same as a day nobody scheduled. */
    val isDayOff: Boolean get() = dutyType == DutyType.OFF
}

/**
 * Which end of a shift a question belongs to.
 *
 * The office decides, per question, in the admin. [BOTH] is asked twice — once opening the shift
 * and once closing it — which is the point of it: "are all issued items accounted for" is a
 * different answer at 6am and at 6pm, and the pair is the evidence.
 */
enum class EvaluationTiming {
    TIME_IN,
    TIME_OUT,
    BOTH,
    ;

    fun appliesTo(type: AttendanceType): Boolean = when (type) {
        AttendanceType.TIME_IN -> this == TIME_IN || this == BOTH
        AttendanceType.TIME_OUT -> this == TIME_OUT || this == BOTH
        // A visit happens mid-round. It is not a boundary and describes no shift.
        AttendanceType.CHECKPOINT -> false
    }

    companion object {
        /**
         * Unknown values fall back to [TIME_OUT], which is where every question lived before the
         * timing existed. A server that adds a third timing must not make an old build ask a
         * question at the wrong end of the shift, or ask nothing at all.
         */
        fun fromWire(value: String?): EvaluationTiming = when (value?.lowercase()) {
            "time_in" -> TIME_IN
            "both" -> BOTH
            else -> TIME_OUT
        }
    }
}

/** One yes/no question put to a guard at one end of their shift. */
data class EvaluationQuestion(
    val id: Long,
    val question: String,
    val sortOrder: Int,
    val timing: EvaluationTiming = EvaluationTiming.TIME_OUT,
)

/** The guard's answer to one of them, carried with the Time Out it describes. */
data class EvaluationAnswer(
    val questionId: Long,
    val answer: Boolean,
)

enum class SyncState { PENDING, SYNCING, SYNCED, FAILED, REJECTED }

/**
 * What a "Sync now" actually did, in both directions.
 *
 * Reported back so the guard is told something true rather than something reassuring. On a
 * replacement handset the number that matters is [downloaded] — it is the difference between "my
 * attendance is gone" and "there it is".
 */
data class SyncOutcome(
    /** Stuck records put back in the queue to be tried again. */
    val requeued: Int = 0,
    /** Records fetched from the server that this device did not already hold. */
    val downloaded: Int = 0,
    /** False when the pull could not run at all — offline, or the server was unreachable. */
    val reachedServer: Boolean = false,
)

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
