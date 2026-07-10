package com.appetiser.guardapp.core.network.mock

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Serves canned responses so the app runs before the backend exists (T-10).
 *
 * It sits in the OkHttp chain rather than replacing [com.appetiser.guardapp.core.network.GuardApi]
 * with a fake, so Retrofit, Moshi, and the KSP-generated adapters all stay on the real code
 * path. A fixture that does not match the contract fails here, not in production.
 *
 * Debug source set only: no mock code is compiled into a release APK.
 *
 * @param loadAsset returns the fixture bytes for a path like `mock/checkpoints.json`, or null.
 */
class MockApiInterceptor(
    private val loadAsset: (String) -> String?,
    private val latencyMillis: Long = 150,
) : Interceptor {

    private val json = "application/json; charset=utf-8".toMediaType()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath.substringAfter("/api/", request.url.encodedPath)
        val method = request.method

        val route = route(method, path)
            ?: return errorResponse(
                chain, 404,
                """{"error":{"code":"not_found","message":"No mock for $method /$path"}}""",
            )

        // Simulate a slow field network so offline-first behaviour is observable by hand.
        if (latencyMillis > 0) Thread.sleep(latencyMillis)

        val body = loadAsset(route.fixture)
            ?: return errorResponse(
                chain, 500,
                """{"error":{"code":"mock_missing","message":"Fixture ${route.fixture} not found"}}""",
            )

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(route.status)
            .message(route.fixture)
            .body(body.toResponseBody(json))
            .addHeader("Content-Type", "application/json")
            .build()
    }

    private data class Route(val fixture: String, val status: Int)

    private fun route(method: String, path: String): Route? = when {
        method == "GET" && path == "profile" -> Route("mock/profile.json", 200)
        method == "GET" && path == "checkpoints" -> Route("mock/checkpoints.json", 200)
        method == "GET" && path == "duties" -> Route("mock/duties.json", 200)
        method == "GET" && path == "announcements" -> Route("mock/announcements.json", 200)
        method == "GET" && path == "settings" -> Route("mock/settings.json", 200)
        method == "GET" && path == "attendance/history" -> Route("mock/attendance_history.json", 200)
        method == "POST" && path == "login" -> Route("mock/login.json", 200)
        method == "POST" && path == "logout" -> Route("mock/empty.json", 204)
        // 201 on first submit. The mock cannot honour idempotency across process death, so it
        // always reports "created"; the 200-on-repeat path is covered by the sync tests.
        method == "POST" && path == "attendance" -> Route("mock/attendance_created.json", 201)
        else -> null
    }

    private fun errorResponse(chain: Interceptor.Chain, code: Int, body: String) =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("mock")
            .body(body.toResponseBody(json))
            .build()
}
