package com.minsu.guardapp.data

import com.minsu.guardapp.core.database.AttendanceEntity
import com.minsu.guardapp.core.database.CheckpointEntity
import com.minsu.guardapp.core.database.DutyEntity
import com.minsu.guardapp.core.database.SyncStatus
import com.minsu.guardapp.core.network.dto.AttendanceDto
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
    allowsTimeInOut = allowsTimeInOut,
    updatedAt = now,
)

fun CheckpointEntity.toDomain() = Checkpoint(
    id = id,
    code = code,
    name = name,
    isActive = status.equals(STATUS_ACTIVE, ignoreCase = true),
    latitude = latitude,
    longitude = longitude,
    allowsTimeInOut = allowsTimeInOut,
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
    timeInEarlyMinutes = timeInEarlyMinutes,
    minVisitsPerCheckpoint = minVisitsPerCheckpoint,
    maintenanceMessage = maintenanceMessage,
)

/**
 * A record the server already holds, rebuilt as a local row.
 *
 * Null when the wire data cannot be trusted into the schema — an attendance type or a timestamp
 * this build does not understand. One unreadable record is skipped; it must not abort the whole
 * download and leave the guard with nothing.
 *
 * Written as [SyncStatus.SYNCED] with its server id: it is, by definition, already uploaded. That
 * also keeps it permanently out of the uploader, which only ever claims PENDING or FAILED rows —
 * important, because [AttendanceEntity.selfiePath] here holds the server's URL rather than a path
 * to a file on this phone. The photo itself is not downloaded; History says so plainly when the
 * file is absent, and the full image remains available in the web portal.
 */
fun AttendanceDto.toEntity(now: Long): AttendanceEntity? {
    val type = EntityAttendanceType.fromWire(attendanceType) ?: return null
    val captured = parseServerTimestamp(capturedAt) ?: return null

    return AttendanceEntity(
        // The key it was captured under, so a re-download lands on the same row and a record this
        // device uploaded is recognised as its own rather than duplicated alongside it.
        id = clientUuid,
        serverId = id,
        userId = userId,
        checkpointId = checkpointId,
        checkpointCode = checkpointCode.orEmpty(),
        attendanceType = type,
        selfiePath = selfieUrl,
        capturedAt = captured,
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        dutiesAcknowledged = dutiesAcknowledged,
        dutiesVersionId = dutiesVersionId,
        deviceId = deviceId,
        syncStatus = SyncStatus.SYNCED,
        createdAt = now,
        updatedAt = now,
    )
}

/**
 * Laravel is not consistent about how it serialises a timestamp across endpoints and casts — an
 * offset, a `Z`, fractional seconds, or a plain space-separated datetime are all in play. Each is
 * tried rather than assuming one, because guessing wrong here does not throw: it silently files a
 * guard's attendance under the wrong instant, or drops it.
 */
private fun parseServerTimestamp(value: String): Long? {
    // Fractional seconds are dropped before matching rather than patterned for. Laravel emits
    // microseconds (`.000000Z`), and SimpleDateFormat's `SSS` consumes digits greedily: it would
    // read `.123456` as 123456 milliseconds and file the record two minutes late. Attendance is
    // recorded to the second, so there is nothing here worth keeping.
    val trimmed = value.trim().replace(FRACTIONAL_SECONDS, "").ifEmpty { return null }

    for ((pattern, zone) in TIMESTAMP_PATTERNS) {
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                // Null where the text carries its own offset and the parser must honour it.
                zone?.let { timeZone = it }
            }.parse(trimmed)
        }.getOrNull()
        if (parsed != null) return parsed.time
    }
    return null
}

private val FRACTIONAL_SECONDS = Regex("""\.\d+""")

/**
 * Pattern to the zone it must be read in. A trailing `Z` is UTC and is matched as a literal, so
 * the zone has to be forced — left to default it would read a UTC instant as local time and shift
 * every downloaded record by the offset, which in Manila is a full eight hours.
 */
private val TIMESTAMP_PATTERNS: List<Pair<String, TimeZone?>> = listOf(
    "yyyy-MM-dd'T'HH:mm:ssXXX" to null,
    "yyyy-MM-dd'T'HH:mm:ss'Z'" to TimeZone.getTimeZone("UTC"),
    "yyyy-MM-dd'T'HH:mm:ss" to TimeZone.getDefault(),
    "yyyy-MM-dd HH:mm:ss" to TimeZone.getDefault(),
)

fun AttendanceEntity.toDomain() = AttendanceRecord(
    id = id,
    checkpointCode = checkpointCode,
    type = when (attendanceType) {
        EntityAttendanceType.TIME_IN -> AttendanceType.TIME_IN
        EntityAttendanceType.TIME_OUT -> AttendanceType.TIME_OUT
        EntityAttendanceType.CHECKPOINT -> AttendanceType.CHECKPOINT
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
