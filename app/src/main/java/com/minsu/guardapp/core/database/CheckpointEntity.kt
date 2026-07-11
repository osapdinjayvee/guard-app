package com.minsu.guardapp.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Locally cached checkpoint, mirroring the backend `qr_checkpoints` table.
 *
 * This cache is what lets a guard scan a QR code with no connectivity: resolution reads here
 * first. See `.docs/Implementation_Plan.md` §2.
 */
@Entity(
    tableName = "checkpoints",
    indices = [Index(value = ["code"], unique = true)],
)
data class CheckpointEntity(
    @PrimaryKey val id: Long,
    /** The value encoded in the QR code. */
    val code: String,
    val name: String,
    val description: String?,
    val latitude: Double?,
    val longitude: Double?,
    /** ACTIVE or DISABLED. A disabled checkpoint must not accept attendance. */
    val status: String,
    val updatedAt: Long,
)
