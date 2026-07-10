package com.appetiser.guardapp.core.network

import com.appetiser.guardapp.core.security.TokenStore
import com.appetiser.guardapp.core.session.SessionEvents
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the Sanctum bearer token, and reacts to its rejection.
 *
 * On a 401 the session is cleared and [SessionEvents] fires so the UI can route to login.
 * **The sync queue is deliberately untouched.** Queued attendance rows stay `PENDING` and
 * upload once the guard re-authenticates; discarding them would destroy captured evidence,
 * which is precisely what the offline-first design exists to prevent.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
    private val sessionEvents: SessionEvents,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // `login` has no token to send, and its 401 means "wrong password", not "session over".
        val isAuthEndpoint = request.url.encodedPath.endsWith("/login")

        val authorized = if (isAuthEndpoint) {
            request
        } else {
            // Blocking is correct here: interceptors run on OkHttp's own dispatcher thread.
            val token = runBlocking { tokenStore.token() }
            if (token == null) {
                request
            } else {
                request.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            }
        }

        val response = chain.proceed(
            authorized.newBuilder().header("Accept", "application/json").build()
        )

        if (response.code == 401 && !isAuthEndpoint) {
            runBlocking {
                tokenStore.clear()
                sessionEvents.notifySessionExpired()
            }
        }

        return response
    }
}
