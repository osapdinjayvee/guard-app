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
}
