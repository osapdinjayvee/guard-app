package com.minsu.guardapp.core.sync

import com.minsu.guardapp.core.database.AttendanceEntity
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.dto.AttendanceDto
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Turns a stored attendance record into the multipart POST /api/attendance and reports the
 * typed outcome. Kept apart from the worker so the mapping is unit-testable without WorkManager.
 */
interface AttendanceUploader {
    suspend fun upload(record: AttendanceEntity): ApiResult<AttendanceDto>
}

class DefaultAttendanceUploader @Inject constructor(
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
) : AttendanceUploader {

    override suspend fun upload(record: AttendanceEntity): ApiResult<AttendanceDto> = errors.call {
        val selfie = File(record.selfiePath)
        val selfiePart = MultipartBody.Part.createFormData(
            name = "selfie",
            filename = selfie.name,
            body = selfie.asRequestBody(JPEG),
        )

        api.submitAttendance(
            clientUuid = record.id.text(),
            checkpointId = record.checkpointId.toString().text(),
            // The wire format is snake_case (`time_in`), not the enum's own name.
            attendanceType = record.attendanceType.wireName.text(),
            // ISO 8601 with the offset, so the server can tell device-capture time from receipt.
            capturedAt = iso8601(record.capturedAt).text(),
            dutiesAcknowledged = record.dutiesAcknowledged.toString().text(),
            latitude = record.latitude?.toString()?.text(),
            longitude = record.longitude?.toString()?.text(),
            accuracy = record.accuracy?.toString()?.text(),
            dutiesVersionId = record.dutiesVersionId?.toString()?.text(),
            deviceId = record.deviceId?.text(),
            selfie = selfiePart,
        ).data
    }

    private fun String.text(): RequestBody = toRequestBody(TEXT)

    private companion object {
        val JPEG = "image/jpeg".toMediaTypeOrNull()
        val TEXT = "text/plain".toMediaTypeOrNull()
    }
}

private fun iso8601(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(millis))
