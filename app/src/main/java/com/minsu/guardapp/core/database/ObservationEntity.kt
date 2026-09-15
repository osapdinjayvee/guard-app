package com.minsu.guardapp.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * A guard's written observation — a broken window, an open gate — and its place in the upload queue.
 *
 * Same contract as [AttendanceEntity]: the row is committed **before any network call**, so a report
 * written at a post with no signal survives airplane mode, app kill and process death until it can be
 * sent. The sync columns mean exactly what they mean there; see [SyncStatus].
 */
@Entity(
    tableName = "observations",
    indices = [
        // The sync worker's selection query.
        Index(value = ["syncStatus", "nextAttemptAt"]),
        Index(value = ["capturedAt"]),
        Index(value = ["serverId"], unique = true),
    ],
)
data class ObservationEntity(
    /**
     * Client-generated UUID, sent as `client_uuid`. The endpoint is idempotent on it, so it is created
     * once and reused on every retry — a fresh one per attempt would duplicate the report whenever a
     * 201 response is lost.
     */
    @PrimaryKey val id: String,

    /** Set on first successful sync. */
    val serverId: Long? = null,

    /** The guard who wrote it. Every read is scoped by this, for the reason given on [AttendanceEntity.userId]. */
    val userId: Long,

    /** At most 5000 characters; the server rejects anything longer. */
    val note: String,

    /** When the observation was written. Device clock; never rewritten. */
    val capturedAt: Long,

    val deviceId: String?,

    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val retryCount: Int = 0,
    val nextAttemptAt: Long? = null,
    val claimedAt: Long? = null,
    val lastError: String? = null,

    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One of the one to five photos attached to an observation.
 *
 * A table of its own rather than a JSON column, because each photo carries its own time and fix — a
 * guard walks between shots — and the server takes them per photo. Cascades on delete, so an
 * observation can never leave rows behind pointing at files.
 */
@Entity(
    tableName = "observation_photos",
    foreignKeys = [
        ForeignKey(
            entity = ObservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["observationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["observationId"])],
)
data class ObservationPhotoEntity(
    @PrimaryKey val id: String,
    val observationId: String,
    /** Upload order. */
    val position: Int,
    /** Absolute path to the JPEG, with its metadata already burned into the pixels. */
    val path: String,
    val capturedAt: Long,
    /** Not nullable: the server requires coordinates on every photo, so none is taken without a fix. */
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
)

/** An observation and its photos — the unit the sync worker uploads. */
data class ObservationWithPhotos(
    @Embedded val observation: ObservationEntity,
    @Relation(parentColumn = "id", entityColumn = "observationId")
    val photos: List<ObservationPhotoEntity>,
)
