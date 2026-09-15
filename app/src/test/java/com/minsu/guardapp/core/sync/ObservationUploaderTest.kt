package com.minsu.guardapp.core.sync

import com.minsu.guardapp.core.database.ObservationEntity
import com.minsu.guardapp.core.database.ObservationPhotoEntity
import com.minsu.guardapp.core.database.ObservationWithPhotos
import com.minsu.guardapp.core.network.ApiError
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
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

/** Pins the multipart shape of `POST /api/observations` to what `SubmitObservationRequest` validates. */
class ObservationUploaderTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var uploader: ObservationUploader

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GuardApi::class.java)
        uploader = DefaultObservationUploader(api, ApiErrorMapper(moshi))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends the note and every photo under bracket-indexed keys`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"data":{"id":31,"client_uuid":"obs-1","status":"new","note":"x","photos":[]}}"""
            )
        )

        val result = uploader.upload(record(photoCount = 2, secondAccuracy = null))

        assertEquals(31L, (result as ApiResult.Success).value.id)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/observations", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))

        val body = request.body.readUtf8()
        listOf(
            "client_uuid", "note", "captured_at",
            "photos[0][file]", "photos[0][captured_at]", "photos[0][latitude]",
            "photos[0][longitude]", "photos[0][accuracy]",
            "photos[1][file]", "photos[1][latitude]", "photos[1][longitude]",
        ).forEach { key -> assertTrue("missing $key", body.contains("name=\"$key\"")) }

        // Nullable fields are omitted, not sent empty: an empty string fails `numeric`.
        assertFalse(body.contains("name=\"photos[1][accuracy]\""))
        assertFalse(body.contains("name=\"device_id\""))
        assertTrue(body.contains("Found a broken window"))
    }

    @Test
    fun `a validation failure is a permanent rejection, not a retry`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"error":{"code":"validation_failed","message":"The submitted data failed validation.",
                   "details":{"photos.0.file":["too big"]}}}"""
            )
        )

        val result = uploader.upload(record(photoCount = 1, secondAccuracy = null))

        assertTrue((result as ApiResult.Failure).error is ApiError.Validation)
    }

    private fun record(photoCount: Int, secondAccuracy: Float?): ObservationWithPhotos {
        val photos = (0 until photoCount).map { index ->
            val file = temp.newFile("photo$index.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            ObservationPhotoEntity(
                id = "p$index",
                observationId = "obs-1",
                position = index,
                path = file.absolutePath,
                capturedAt = 1_757_000_000_000L + index,
                latitude = 13.4115,
                longitude = 121.1803,
                accuracy = if (index == 0) 4.5f else secondAccuracy,
            )
        }
        return ObservationWithPhotos(
            observation = ObservationEntity(
                id = "obs-1",
                userId = 7,
                note = "Found a broken window near the main gate.",
                capturedAt = 1_757_000_000_000L,
                deviceId = null,
                createdAt = 0,
                updatedAt = 0,
            ),
            photos = photos,
        )
    }
}
