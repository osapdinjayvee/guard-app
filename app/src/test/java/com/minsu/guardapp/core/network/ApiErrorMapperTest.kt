package com.minsu.guardapp.core.network

import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

class ApiErrorMapperTest {

    private val mapper = ApiErrorMapper(Moshi.Builder().build())
    private val json = "application/json".toMediaType()

    private fun http(code: Int, body: String = ""): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody(json)))

    private suspend fun failWith(e: Throwable): ApiError =
        (mapper.call<Unit> { throw e } as ApiResult.Failure).error

    @Test
    fun `success passes the value through`() = runTest {
        val result = mapper.call { 42 }
        assertEquals(ApiResult.Success(42), result)
    }

    @Test
    fun `timeouts and dns failures are retriable network errors`() = runTest {
        assertTrue(failWith(SocketTimeoutException()) is ApiError.Network)
        assertTrue(failWith(IOException("dns")) is ApiError.Network)
        assertTrue(failWith(IOException("dns")).isRetriable)
    }

    @Test
    fun `401 is Unauthorized, and is not retried`() = runTest {
        val error = failWith(http(401, """{"error":{"code":"unauthenticated","message":"x"}}"""))

        assertEquals(ApiError.Unauthorized, error)
        // The record stays PENDING and syncs after re-login; it is never dropped.
        assertFalse(error.isRetriable)
    }

    @Test
    fun `401 with invalid_credentials is distinguished from an expired session`() = runTest {
        val error = failWith(http(401, """{"error":{"code":"invalid_credentials","message":"x"}}"""))
        assertEquals(ApiError.InvalidCredentials, error)
    }

    @Test
    fun `409 becomes a Rejected carrying the machine-readable code`() = runTest {
        val error = failWith(
            http(409, """{"error":{"code":"checkpoint_disabled","message":"That checkpoint is retired."}}""")
        )

        error as ApiError.Rejected
        assertEquals("checkpoint_disabled", error.code)
        assertEquals("That checkpoint is retired.", error.message)
        assertFalse("a rejected record must never be retried", error.isRetriable)
    }

    /** A malformed body must not downgrade a permanent rejection into a retry. */
    @Test
    fun `409 with an unparsable body is still a rejection`() = runTest {
        val error = failWith(http(409, "<html>gateway</html>"))

        assertTrue(error is ApiError.Rejected)
        assertEquals("conflict", (error as ApiError.Rejected).code)
        assertFalse(error.isRetriable)
    }

    @Test
    fun `422 carries field errors`() = runTest {
        val error = failWith(
            http(
                422,
                """{"error":{"code":"validation_failed","message":"Invalid.",
                   "details":{"selfie":["The selfie is required."]}}}""",
            )
        )

        error as ApiError.Validation
        assertEquals(listOf("The selfie is required."), error.fieldErrors["selfie"])
    }

    @Test
    fun `413 is a payload limit, not a server fault`() = runTest {
        assertEquals(ApiError.PayloadTooLarge, failWith(http(413)))
        assertFalse(failWith(http(413)).isRetriable)
    }

    @Test
    fun `5xx is retriable`() = runTest {
        val error = failWith(http(503, """{"error":{"code":"unavailable","message":"Down."}}"""))

        error as ApiError.Server
        assertEquals(503, error.status)
        assertTrue(error.isRetriable)
    }

    @Test
    fun `an unmodelled status does not masquerade as success`() = runTest {
        assertTrue(failWith(http(418)) is ApiError.Unexpected)
    }
}
