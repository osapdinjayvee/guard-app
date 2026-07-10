package com.appetiser.guardapp.core.network

import com.appetiser.guardapp.core.security.TokenStore
import com.appetiser.guardapp.core.session.SessionEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private class FakeTokenStore(var stored: String?) : TokenStore {
        var clearCount = 0
        override suspend fun token(): String? = stored
        override suspend fun save(token: String) { stored = token }
        override suspend fun clear() { stored = null; clearCount++ }
    }

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var sessionEvents: SessionEvents
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokenStore = FakeTokenStore("1|secret-token")
        sessionEvents = SessionEvents()
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore, sessionEvents))
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun get(path: String) = client.newCall(
        Request.Builder().url(server.url(path)).build()
    ).execute().also { it.close() }

    private fun post(path: String) = client.newCall(
        Request.Builder().url(server.url(path)).post(okhttp3.internal.EMPTY_REQUEST).build()
    ).execute().also { it.close() }

    @Test
    fun `attaches the bearer token and accepts json`() {
        server.enqueue(MockResponse().setBody("{}"))

        get("/api/profile")

        val request = server.takeRequest()
        assertEquals("Bearer 1|secret-token", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `sends no authorization header when logged out`() {
        tokenStore.stored = null
        server.enqueue(MockResponse().setBody("{}"))

        get("/api/checkpoints")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `never sends a token to login`() {
        server.enqueue(MockResponse().setBody("{}"))

        post("/api/login")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a 401 clears the session and signals the ui`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthenticated","message":"x"}}"""))

        var signalled = false
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            sessionEvents.sessionExpired.collect { signalled = true }
        }

        get("/api/profile")

        assertEquals("token cleared", 1, tokenStore.clearCount)
        assertNull(tokenStore.stored)
        assertTrue("ui must be told to route to login", signalled)
        collector.cancel()
    }

    /**
     * A 401 from `login` means "wrong password", not "your session ended". Clearing here would
     * be harmless but the event would wrongly push an already-logged-out user to login again.
     */
    @Test
    fun `a 401 from login does not clear the session`() {
        tokenStore.stored = null
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_credentials","message":"x"}}"""))

        post("/api/login")

        assertEquals(0, tokenStore.clearCount)
    }

    @Test
    fun `a 500 leaves the session intact`() {
        server.enqueue(MockResponse().setResponseCode(500))

        get("/api/profile")

        assertEquals(0, tokenStore.clearCount)
        assertEquals("1|secret-token", tokenStore.stored)
    }

    @Test
    fun `notifying with no collector does not suspend or throw`() {
        // A background sync worker can hit a 401 with no UI on screen.
        server.enqueue(MockResponse().setResponseCode(401))

        get("/api/attendance/history")

        assertEquals(1, tokenStore.clearCount)
        assertFalse(tokenStore.stored == "1|secret-token")
    }
}
