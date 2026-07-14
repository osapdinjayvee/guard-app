package com.minsu.guardapp.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A captured attendance record and its place in the upload queue.
 *
 * This row is written and committed **before any network call**. Everything about the sync
 * columns exists to make that durable: the record survives airplane mode, app kill, and
 * process death mid-upload.
 */
@Entity(
    tableName = "attendance",
    indices = [
        // The sync worker's selection query: eligible rows, cheapest first.
        Index(value = ["syncStatus", "nextAttemptAt"]),
        // History and Reports order by capture time.
        Index(value = ["capturedAt"]),
        // A server id appears only once synced. SQLite allows many NULLs in a unique index,
        // which is exactly right: unsynced rows have none.
        Index(value = ["serverId"], unique = true),
    ],
)
data class AttendanceEntity(
    /**
     * Client-generated UUID, also the idempotency key. Created once, before the first upload
     * attempt, and reused on every retry — regenerating per attempt would defeat server-side
     * deduplication and produce a duplicate record whenever a 201 response is lost.
     */
    @PrimaryKey val id: String,

    /** Set on first successful sync. Null until then. */
    val serverId: Long? = null,

    /**
     * The guard this record belongs to.
     *
     * A handset is passed between shifts, and Room does not know that. This column has always been
     * written; until now nothing *read* it, so every query returned every guard's records. The next
     * guard to sign in inherited the last one's attendance — their history, their round, their Time
     * In. That is not a display bug: it is one guard's evidence presented as another's.
     */
    val userId: Long,
    val checkpointId: Long,
    /** Denormalised so History renders without a join. */
    val checkpointCode: String,
    val attendanceType: AttendanceType,

    /** Absolute path to the JPEG with the metadata already burned into its pixels. */
    val selfiePath: String,

    /** Device clock at shutter. May precede upload by hours; never rewritten. */
    val capturedAt: Long,

    val latitude: Double?,
    val longitude: Double?,
    /** Metres. Null only when the GPS policy permitted submission without a fix. */
    val accuracy: Float?,

    val dutiesAcknowledged: Boolean,
    /** Which duties revision was acknowledged; a stale cache is otherwise unattributable. */
    val dutiesVersionId: Long?,

    val deviceId: String?,

    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val retryCount: Int = 0,
    /** Row is ineligible for claiming until now >= this. Null means eligible immediately. */
    val nextAttemptAt: Long? = null,
    /** When the current SYNCING claim was taken; drives the stale-claim sweep. */
    val claimedAt: Long? = null,
    /** Surfaced in History detail for FAILED and REJECTED rows. */
    val lastError: String? = null,

    /**
     * The post-shift self-evaluation, as JSON, for a Time Out.
     *
     * Stored on the record rather than in a table of its own. The answers exist only to be uploaded
     * with the attendance they describe — the phone never queries them, reports on them, or shows
     * them again — and a join table would buy nothing but a second thing to keep in step with a
     * record that is already the unit of work for the sync queue. Null for a Time In or a checkpoint
     * visit, which carry no evaluation at all.
     */
    val evaluationsJson: String? = null,

    val createdAt: Long,
    val updatedAt: Long,
)

enum class AttendanceType {
    TIME_IN,
    TIME_OUT,

    /** A patrol visit. Only a roving guard records one. */
    CHECKPOINT,
    ;

    /**
     * How the API spells this. Kept separate from [name], which Room persists — changing the wire
     * format must not silently rewrite what is already in the database.
     */
    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): AttendanceType? =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
    }
}

class AttendanceTypeConverter {
    @androidx.room.TypeConverter
    fun toType(value: String): AttendanceType = AttendanceType.valueOf(value)

    @androidx.room.TypeConverter
    fun fromType(type: AttendanceType): String = type.name
}
