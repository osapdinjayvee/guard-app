package com.minsu.guardapp.core.sync

import com.minsu.guardapp.core.database.ObservationWithPhotos
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.dto.ObservationDto
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject

/**
 * Turns a stored observation into the multipart `POST /api/observations`. Kept apart from the worker
 * so the wire format is testable without WorkManager.
 */
interface ObservationUploader {
    suspend fun upload(record: ObservationWithPhotos): ApiResult<ObservationDto>
}

class DefaultObservationUploader @Inject constructor(
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
) : ObservationUploader {

    override suspend fun upload(record: ObservationWithPhotos): ApiResult<ObservationDto> = errors.call {
        val observation = record.observation

        val parts = buildList {
            add(field("client_uuid", observation.id))
            add(field("note", observation.note))
            add(field("captured_at", iso8601(observation.capturedAt)))
            observation.deviceId?.let { add(field("device_id", it)) }

            // Indexed by upload order rather than by the stored position, so the keys are always
            // 0..n-1 with no gaps — a gap would read to the server as a photo missing its file.
            record.photos.sortedBy { it.position }.forEachIndexed { index, photo ->
                val file = File(photo.path)
                add(
                    MultipartBody.Part.createFormData(
                        name = "photos[$index][file]",
                        filename = file.name,
                        body = file.asRequestBody(JPEG),
                    )
                )
                add(field("photos[$index][captured_at]", iso8601(photo.capturedAt)))
                add(field("photos[$index][latitude]", photo.latitude.toString()))
                add(field("photos[$index][longitude]", photo.longitude.toString()))
                photo.accuracy?.let { add(field("photos[$index][accuracy]", it.toString())) }
            }
        }

        api.submitObservation(parts).data
    }

    private fun field(name: String, value: String) = MultipartBody.Part.createFormData(name, value)

    private companion object {
        val JPEG = "image/jpeg".toMediaTypeOrNull()
    }
}
