package com.minsu.guardapp.data

import com.minsu.guardapp.core.database.AttendanceEntity
import com.minsu.guardapp.core.database.CheckpointEntity
import com.minsu.guardapp.core.database.DutyEntity
import com.minsu.guardapp.core.database.SyncStatus
import com.minsu.guardapp.core.network.dto.CheckpointDto
import com.minsu.guardapp.core.network.dto.DutyDto
import com.minsu.guardapp.core.network.dto.MobileSettingsDto
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.domain.model.GpsFailurePolicy
import com.minsu.guardapp.domain.model.SyncState
import com.minsu.guardapp.core.database.AttendanceType as EntityAttendanceType

private const val STATUS_ACTIVE = "ACTIVE"

fun CheckpointDto.toEntity(now: Long) = CheckpointEntity(
    id = id,
    code = code,
    slug = slug,
    name = name,
    description = description,
    latitude = latitude,
    longitude = longitude,
    // Canonicalised on the way in. The backend spells this `active`, the cache queries for
    // `ACTIVE`, and SQLite's `=` is case-sensitive — storing the wire value verbatim leaves every
    // checkpoint present but invisible to `observeActive()`.
    status = status.uppercase(),
    updatedAt = now,
)

fun CheckpointEntity.toDomain() = Checkpoint(
    id = id,
    code = code,
    name = name,
    isActive = status.equals(STATUS_ACTIVE, ignoreCase = true),
    latitude = latitude,
    longitude = longitude,
)

fun DutyDto.toEntity(now: Long) = DutyEntity(
    id = id,
    title = title,
    content = content,
    active = active,
    effectiveDate = effectiveDate,
    updatedAt = now,
)

fun DutyEntity.toDomain() = Duty(id = id, title = title, content = content)

fun MobileSettingsDto.toDomain() = AppSettings(
    geofenceRadiusMetres = geofenceRadiusM,
    gpsAccuracyThresholdMetres = gpsAccuracyThresholdM,
    // An unrecognised policy string falls back to BLOCK rather than silently allowing
    // location-less attendance.
    gpsFailurePolicy = GpsFailurePolicy.parse(gpsFailurePolicy),
    gpsTimeoutSeconds = gpsTimeoutSeconds,
    imageQuality = imageQuality,
    imageMaxDimensionPx = imageMaxDimensionPx,
    maintenanceMessage = maintenanceMessage,
)

fun AttendanceEntity.toDomain() = AttendanceRecord(
    id = id,
    checkpointCode = checkpointCode,
    type = when (attendanceType) {
        EntityAttendanceType.TIME_IN -> AttendanceType.TIME_IN
        EntityAttendanceType.TIME_OUT -> AttendanceType.TIME_OUT
    },
    capturedAt = capturedAt,
    selfiePath = selfiePath,
    latitude = latitude,
    longitude = longitude,
    accuracyMetres = accuracy,
    syncState = when (syncStatus) {
        SyncStatus.PENDING -> SyncState.PENDING
        SyncStatus.SYNCING -> SyncState.SYNCING
        SyncStatus.SYNCED -> SyncState.SYNCED
        SyncStatus.FAILED -> SyncState.FAILED
        SyncStatus.REJECTED -> SyncState.REJECTED
    },
    lastError = lastError,
)
