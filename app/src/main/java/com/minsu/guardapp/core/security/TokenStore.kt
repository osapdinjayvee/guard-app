package com.minsu.guardapp.core.security

import kotlinx.coroutines.flow.Flow

/**
 * Where the Sanctum bearer token lives.
 *
 * Deliberately not `androidx.security:security-crypto` — Jetpack Security is deprecated with
 * no successor. See `.docs/Implementation_Plan.md` §2.
 */
interface TokenStore {

    /** Emits whether a token is stored. Drives the auth gate. */
    val hasToken: Flow<Boolean>

    /** Null when logged out, or when the ciphertext can no longer be decrypted. */
    suspend fun token(): String?

    suspend fun save(token: String)

    /** Idempotent: safe to call when already logged out. */
    suspend fun clear()
}
