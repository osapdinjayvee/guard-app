package com.minsu.guardapp.core.network

/**
 * Every failure the client can act on, named. Callers switch on these, never on HTTP codes or
 * on `message`, which is human-facing prose the backend may reword.
 */
sealed interface ApiError {

    /** No network, DNS failure, timeout. Always retriable. */
    data class Network(val cause: Throwable) : ApiError

    /** Token missing, expired, or revoked. The session is cleared; the sync queue is not. */
    data object Unauthorized : ApiError

    /** Wrong username or password. Distinct from [Unauthorized]: no session existed to clear. */
    data object InvalidCredentials : ApiError

    /**
     * The server looked and there is no such thing. Distinct from every other failure, which mean
     * only that we *could not look* — telling a guard their checkpoint does not exist when the
     * truth is that the network is down is a lie, and it is the lie that sends them looking for a
     * different door.
     */
    data object NotFound : ApiError

    /**
     * The server permanently refuses this record: a disabled or unknown checkpoint, or an
     * out-of-sequence attendance type. Retrying will never succeed, so the record moves to
     * `REJECTED` and is surfaced to the guard rather than retried or deleted.
     */
    data class Rejected(val code: String, val message: String) : ApiError

    data class Validation(val message: String, val fieldErrors: Map<String, List<String>>) : ApiError

    /** Selfie too large for the server's limit. */
    data object PayloadTooLarge : ApiError

    /** 5xx. Retriable: the request may succeed later. */
    data class Server(val status: Int, val message: String) : ApiError

    /** A response we could not parse, or anything else unforeseen. */
    data class Unexpected(val cause: Throwable) : ApiError
}

/** True when a sync worker should schedule another attempt rather than give up. */
val ApiError.isRetriable: Boolean
    get() = when (this) {
        is ApiError.Network, is ApiError.Server -> true
        // Unauthorized is not retriable *now*, but the record stays PENDING and will be
        // retried once the guard re-authenticates. It must never be dropped.
        ApiError.Unauthorized -> false
        is ApiError.Rejected, is ApiError.Validation, ApiError.PayloadTooLarge,
        ApiError.InvalidCredentials, ApiError.NotFound, is ApiError.Unexpected,
        -> false
    }

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.value
