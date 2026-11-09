package com.minsu.guardapp.core.sync

import com.minsu.guardapp.core.database.AttendanceEntity
import com.minsu.guardapp.core.database.AttendanceType
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.data.EvaluationJson
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class AttendanceUploaderTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: GuardApi
    private lateinit var uploader: AttendanceUploader

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GuardApi::class.java)
        uploader = DefaultAttendanceUploader(api, ApiErrorMapper(moshi), EvaluationJson(moshi))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `offline sync sends the marker and preserves nullable gps and captured timestamp`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(responseBody()))

        val capturedAt = 1_783_663_331_000L
        val result = uploader.upload(
            record(
                capturedAt = capturedAt,
                latitude = null,
                longitude = null,
                accuracy = null,
                evaluationsJson = "{\"evaluations\":[{\"question_id\":123,\"answer\":true}]}",
            )
        )

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("true", request.getHeader("X-Offline-Sync"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"client_uuid\""))
        assertTrue(body.contains("name=\"captured_at\""))
        assertTrue(body.contains(iso8601(capturedAt)))
        assertFalse("missing GPS is not sent as a fake zero", body.contains("name=\"latitude\""))
        assertFalse("missing GPS is not sent as a fake zero", body.contains("name=\"longitude\""))
        assertFalse("missing GPS is not sent as a fake zero", body.contains("name=\"accuracy\""))
        assertTrue(body.contains("[{\"question_id\":123,\"answer\":true}]"))
    }

    @Test
    fun `live attendance request omits the offline marker`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody(responseBody()))
        val selfie = temp.newFile("live.jpg")

        api.submitAttendance(
            offlineSync = null,
            clientUuid = "live-id".text(),
            checkpointId = "1".text(),
            attendanceType = "time_in".text(),
            capturedAt = iso8601(1_783_663_331_000L).text(),
            dutiesAcknowledged = "false".text(),
            latitude = "14.6".text(),
            longitude = "120.98".text(),
            accuracy = "8.0".text(),
            dutiesVersionId = null,
            evaluations = null,
            deviceId = null,
            selfie = MultipartBody.Part.createFormData(
                "selfie", selfie.name, selfie.asRequestBody("image/jpeg".toMediaTypeOrNull()),
            ),
        )

        assertEquals(null, server.takeRequest().getHeader("X-Offline-Sync"))
    }

    private fun record(
        capturedAt: Long,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
        evaluationsJson: String? = null,
    ) =
        AttendanceEntity(
            id = "offline-id",
            userId = 7,
            checkpointId = 1,
            checkpointCode = "GATE-A",
            attendanceType = AttendanceType.TIME_IN,
            selfiePath = temp.newFile("selfie.jpg").absolutePath,
            capturedAt = capturedAt,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            dutiesAcknowledged = false,
            dutiesVersionId = null,
            deviceId = null,
            createdAt = capturedAt,
            updatedAt = capturedAt,
            evaluationsJson = evaluationsJson,
        )

    private fun responseBody() =
        """{"data":{"id":501,"client_uuid":"offline-id","user_id":7,"qr_checkpoint_id":1,"attendance_type":"time_in","selfie_url":"https://example.test/selfie.jpg","captured_at":"2026-07-08T09:15:00+08:00","received_at":"2026-07-08T09:15:01+08:00","duties_acknowledged":false}}"""

    private fun String.text(): RequestBody = this.toRequestBody("text/plain".toMediaTypeOrNull())
}
