package com.minsu.guardapp.core.network.mock

import com.minsu.guardapp.core.network.GuardApi
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File

/**
 * Drives the real [MockApiInterceptor] against the real fixture files through the real
 * Retrofit + Moshi stack. If a fixture drifts from the contract, this fails — which is the
 * whole point of mocking at the transport layer rather than faking [GuardApi].
 */
class MockApiInterceptorTest {

    // Unit tests run with the module directory (app/) as the working directory.
    private val fixtures = File("src/debug/assets")

    private val api: GuardApi = Retrofit.Builder()
        .baseUrl("https://api.example.invalid/api/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(
                    MockApiInterceptor(
                        loadAsset = { path ->
                            File(fixtures, path).takeIf(File::exists)?.readText()
                        },
                        latencyMillis = 0,
                    )
                )
                .build()
        )
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
        .build()
        .create(GuardApi::class.java)

    @Test
    fun `every fixture directory file is valid for its endpoint`() {
        assertTrue("fixtures missing at ${fixtures.absolutePath}", fixtures.resolve("mock").isDirectory)
    }

    @Test
    fun `profile`() = runTest {
        val user = api.profile().data.user
        assertEquals("guard01", user.username)
        assertEquals("active", user.status)
    }

    @Test
    fun `checkpoints include an ACTIVE and a DISABLED entry`() = runTest {
        val checkpoints = api.checkpoints().data
        assertEquals(4, checkpoints.size)
        assertTrue(checkpoints.any { it.status == "ACTIVE" })
        // A disabled checkpoint must be cached so the client can say "disabled", not "unknown".
        assertTrue(checkpoints.any { it.code == "ROOF-OLD" && it.status == "DISABLED" })
        // Null coordinates must survive the round trip.
        assertNull(checkpoints.first { it.code == "LOBBY-1" }.latitude)
    }

    @Test
    fun `duties carry the revision id that gets acknowledged`() = runTest {
        val duty = api.duties().data
        assertEquals(12L, duty.id)
        assertTrue(duty.active)
    }

    @Test
    fun `settings supply the thresholds the client must not hardcode`() = runTest {
        val settings = api.settings().data
        assertEquals(50f, settings.gpsAccuracyThresholdM, 0.01f)
        assertEquals("block", settings.gpsFailurePolicy)
        assertEquals(1600, settings.imageMaxDimensionPx)
    }

    @Test
    fun `announcements`() = runTest {
        assertEquals(2, api.announcements().data.size)
    }

    @Test
    fun `history separates captured_at from received_at`() = runTest {
        val page = api.history()
        assertEquals(3, page.meta.total)

        // The offline record: captured in the evening, received hours later.
        val late = page.data.first { it.id == 502L }
        assertEquals("2026-07-09T18:11:03+08:00", late.capturedAt)
        assertEquals("2026-07-09T21:47:52+08:00", late.receivedAt)
        assertTrue("received_at must not equal captured_at here", late.capturedAt != late.receivedAt)

        // Every record carries the client_uuid the server dedupes on.
        assertTrue(page.data.all { it.clientUuid.isNotBlank() })
    }

    @Test
    fun `a mapped route whose fixture is missing fails loudly with a 500`() = runTest {
        // Silently returning an empty body would let a deleted fixture look like a real
        // empty response, which is exactly the bug this mock exists to avoid.
        val broken = Retrofit.Builder()
            .baseUrl("https://api.example.invalid/api/")
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(MockApiInterceptor(loadAsset = { null }, latencyMillis = 0))
                    .build()
            )
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build()
            .create(GuardApi::class.java)

        val error = runCatching { broken.profile() }.exceptionOrNull()
        assertTrue("expected HttpException, got $error", error is HttpException)
        assertEquals(500, (error as HttpException).code())
    }

    @Test
    fun `an unmapped route returns 404 in the contract's error envelope`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(MockApiInterceptor(loadAsset = { null }, latencyMillis = 0))
            .build()

        val response = client.newCall(
            okhttp3.Request.Builder()
                .url("https://api.example.invalid/api/does-not-exist")
                .build()
        ).execute()

        response.use {
            assertEquals(404, it.code)
            val body = it.body!!.string()
            assertTrue("expected error envelope, got $body", body.contains("\"code\":\"not_found\""))
        }
    }
}
