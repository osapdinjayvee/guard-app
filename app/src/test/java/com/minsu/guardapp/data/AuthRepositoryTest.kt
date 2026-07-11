package com.minsu.guardapp.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.longPreferencesKey
import com.minsu.guardapp.core.network.ApiError
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.dto.AnnouncementDto
import com.minsu.guardapp.core.network.dto.AttendanceDto
import com.minsu.guardapp.core.network.dto.CheckpointDto
import com.minsu.guardapp.core.network.dto.DutyDto
import com.minsu.guardapp.core.network.dto.Envelope
import com.minsu.guardapp.core.network.dto.LoginRequest
import com.minsu.guardapp.core.network.dto.LoginResponse
import com.minsu.guardapp.core.network.dto.MobileSettingsDto
import com.minsu.guardapp.core.network.dto.PagedEnvelope
import com.minsu.guardapp.core.network.dto.ProfileDto
import com.minsu.guardapp.core.network.dto.UserDto
import com.minsu.guardapp.testing.FakeGuardApi
import com.minsu.guardapp.testing.FakeTokenStore
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response

class AuthRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val errors = ApiErrorMapper(Moshi.Builder().build())
    private val tokenStore = FakeTokenStore()
    private val profileId = longPreferencesKey("profile_id")

    private fun dataStore() = PreferenceDataStoreFactory.create {
        tmp.newFile("auth_${System.nanoTime()}.preferences_pb")
    }

    private fun http(code: Int, body: String) =
        HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    private val user = UserDto(7, "Juan Dela Cruz", "guard01", status = "active")

    @Test
    fun `a successful login stores the token and caches the profile`() = runTest {
        val store = dataStore()
        val repo = DefaultAuthRepository(
            api = object : FakeGuardApi() {
                override suspend fun login(request: LoginRequest) = LoginResponse("1|token", user)
            },
            tokenStore = tokenStore, dataStore = store, errors = errors,
        )

        val result = repo.login("guard01", "secret")

        assertTrue(result is ApiResult.Success)
        assertEquals("1|token", tokenStore.stored)
        // Cached so Home renders a name on the first frame instead of a placeholder.
        assertEquals(7L, store.data.first()[profileId])
        assertTrue(repo.isAuthenticated.first())
    }

    @Test
    fun `the username is trimmed before it is sent`() = runTest {
        var sent: String? = null
        val repo = DefaultAuthRepository(
            api = object : FakeGuardApi() {
                override suspend fun login(request: LoginRequest): LoginResponse {
                    sent = request.username
                    return LoginResponse("1|token", user)
                }
            },
            tokenStore = tokenStore, dataStore = dataStore(), errors = errors,
        )

        repo.login("  guard01  ", "secret")

        assertEquals("guard01", sent)
    }

    /**
     * A half-written session — profile cached, no token — would look signed in while every
     * request 401s. Nothing may be written unless the whole login succeeded.
     */
    @Test
    fun `a rejected login writes neither token nor profile`() = runTest {
        val store = dataStore()
        val repo = DefaultAuthRepository(
            api = object : FakeGuardApi() {
                override suspend fun login(request: LoginRequest): LoginResponse =
                    throw http(401, """{"error":{"code":"invalid_credentials","message":"x"}}""")
            },
            tokenStore = tokenStore, dataStore = store, errors = errors,
        )

        val result = repo.login("guard01", "wrong")

        assertEquals(ApiError.InvalidCredentials, (result as ApiResult.Failure).error)
        assertNull("no token", tokenStore.stored)
        assertNull("no cached profile", store.data.first()[profileId])
        assertFalse(repo.isAuthenticated.first())
    }

    /** A guard with no signal must still be able to sign out. */
    @Test
    fun `logout clears the local session even when the server call fails`() = runTest {
        val store = dataStore()
        val repo = DefaultAuthRepository(
            api = object : FakeGuardApi() {
                override suspend fun login(request: LoginRequest) = LoginResponse("1|token", user)
                override suspend fun logout() = throw java.io.IOException("offline")
            },
            tokenStore = tokenStore, dataStore = store, errors = errors,
        )
        repo.login("guard01", "secret")

        repo.logout()

        assertNull(tokenStore.stored)
        assertNull(store.data.first()[profileId])
        assertFalse(repo.isAuthenticated.first())
    }
}
