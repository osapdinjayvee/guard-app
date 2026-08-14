package com.minsu.guardapp.core.network

import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Smoke test for the network stack: Retrofit plus the KSP-generated Moshi adapters.
 * Proves the codegen runs and the snake_case wire format maps onto the DTOs.
 */
class GuardApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GuardApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build()
            .create(GuardApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses the checkpoint envelope, including null optional fields`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[
                  {"id":1,"code":"GATE-A","name":"Main Gate","description":"North entrance",
                   "latitude":14.5995,"longitude":120.9842,"status":"ACTIVE"},
                  {"id":4,"code":"ROOF-OLD","name":"Rooftop","description":null,
                   "latitude":null,"longitude":null,"status":"DISABLED"}
                ]}
                """.trimIndent()
            )
        )

        val checkpoints = api.checkpoints().data

        assertEquals(2, checkpoints.size)
        assertEquals("GATE-A", checkpoints[0].code)
        assertEquals(14.5995, checkpoints[0].latitude!!, 0.0001)
        assertEquals("DISABLED", checkpoints[1].status)
        assertNull(checkpoints[1].latitude)
        assertEquals("/api/checkpoints", server.takeRequest().path)
    }

    @Test
    fun `settings fall back to documented defaults when keys are absent`() = runTest {
        // Adding or omitting a settings key must not be a breaking change.
        server.enqueue(MockResponse().setBody("""{"data":{}}"""))

        val settings = api.settings().data

        assertEquals(50f, settings.gpsAccuracyThresholdM, 0.01f)
        assertEquals("block", settings.gpsFailurePolicy)
        assertEquals(80, settings.imageQuality)
        assertNull(settings.maintenanceMessage)
    }

    @Test
    fun `history parses pagination meta`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[],"meta":{"page":2,"per_page":25,"total":51}}
                """.trimIndent()
            )
        )

        val page = api.history(page = 2)

        assertEquals(2, page.meta.page)
        assertEquals(51, page.meta.total)
        assertEquals("/api/attendance/history?page=2&per_page=25", server.takeRequest().path)
    }

    /**
     * A field the server stops sending must not be able to fail the parse.
     *
     * This is not hypothetical. `duties_acknowledged` was removed from the attendance response
     * while declared here as a required Boolean, so Moshi threw while reading a body the server
     * had already answered 201 to. The record was safely stored and every guard saw it marked
     * Rejected — an upload that worked, reported as an upload that was refused.
     *
     * The client makes no decision from that field. Nothing it does not act on may be required.
     */
    @Test
    fun `an attendance parses without the fields the client does not act on`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"data":[{"id":9,"client_uuid":"abc","user_id":3,"qr_checkpoint_id":4,
                 "attendance_type":"CHECKPOINT","selfie_url":"https://example.test/s.jpg",
                 "captured_at":"2026-08-14T08:47:00+08:00","received_at":"2026-08-14T08:47:05+08:00"}],
                 "meta":{"page":1,"per_page":25,"total":1}}
                """.trimIndent()
            )
        )

        val record = api.history(page = 1).data.single()

        assertEquals(9L, record.id)
        assertEquals("CHECKPOINT", record.attendanceType)
        // Absent, so the documented default stands rather than the parse failing.
        assertEquals(false, record.dutiesAcknowledged)
        assertNull(record.dutiesVersionId)
    }
}
