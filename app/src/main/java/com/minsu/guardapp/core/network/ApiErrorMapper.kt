package com.minsu.guardapp.core.network

import com.minsu.guardapp.core.network.dto.ErrorEnvelope
import com.squareup.moshi.Moshi
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns exceptions and HTTP status codes into [ApiError].
 *
 * The status decides the *class* of failure; `error.code` from the envelope carries the
 * specific reason. A 409 without a parsable body is still a rejection — the client must not
 * fall back to "retry" just because the server's prose was malformed.
 */
@Singleton
class ApiErrorMapper @Inject constructor(moshi: Moshi) {

    private val adapter = moshi.adapter(ErrorEnvelope::class.java)

    suspend fun <T> call(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        ApiResult.Failure(fromHttp(e))
    } catch (e: IOException) {
        // OkHttp wraps DNS failures, timeouts, and socket resets here.
        ApiResult.Failure(ApiError.Network(e))
    } catch (e: Exception) {
        ApiResult.Failure(ApiError.Unexpected(e))
    }

    private fun fromHttp(e: HttpException): ApiError {
        val envelope = runCatching {
            e.response()?.errorBody()?.string()?.takeIf(String::isNotBlank)?.let(adapter::fromJson)
        }.getOrNull()

        val code = envelope?.error?.code.orEmpty()
        val message = envelope?.error?.message.orEmpty()

        return when (e.code()) {
            401 -> if (code == "invalid_credentials") {
                ApiError.InvalidCredentials
            } else {
                ApiError.Unauthorized
            }
            409 -> ApiError.Rejected(
                code = code.ifEmpty { "conflict" },
                message = message.ifEmpty { "The server refused this record." },
            )
            404 -> ApiError.NotFound
            413 -> ApiError.PayloadTooLarge
            422 -> ApiError.Validation(
                message = message.ifEmpty { "The request failed validation." },
                fieldErrors = envelope?.error?.details.orEmpty(),
            )
            in 500..599 -> ApiError.Server(e.code(), message.ifEmpty { "Server error." })
            else -> ApiError.Unexpected(e)
        }
    }
}
