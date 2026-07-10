package com.appetiser.guardapp.core.network

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
 * Smoke test for the network stack: Retrofit + the KSP-generated Moshi adapter.
 * Proves the codegen actually runs and the snake_case wire format maps onto the DTO.
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
    fun `parses the checkpoint list, including null optional fields`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "id": 1,
                    "code": "GATE-A",
                    "name": "Main Gate",
                    "description": "North entrance",
                    "latitude": 14.5995,
                    "longitude": 120.9842,
                    "status": "ACTIVE"
                  },
                  {
                    "id": 2,
                    "code": "GATE-B",
                    "name": "Service Entrance",
                    "description": null,
                    "latitude": null,
                    "longitude": null,
                    "status": "DISABLED"
                  }
                ]
                """.trimIndent()
            )
        )

        val checkpoints = api.checkpoints()

        assertEquals(2, checkpoints.size)
        assertEquals("GATE-A", checkpoints[0].code)
        assertEquals("Main Gate", checkpoints[0].name)
        assertEquals(14.5995, checkpoints[0].latitude!!, 0.0001)
        assertEquals("DISABLED", checkpoints[1].status)
        assertNull(checkpoints[1].latitude)

        assertEquals("/api/checkpoints", server.takeRequest().path)
    }
}
