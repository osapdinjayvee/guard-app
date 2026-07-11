package com.minsu.guardapp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.dto.LoginRequest
import com.minsu.guardapp.core.network.map
import com.minsu.guardapp.core.security.TokenStore
import com.minsu.guardapp.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val api: GuardApi,
    private val tokenStore: TokenStore,
    private val dataStore: DataStore<Preferences>,
    private val errors: ApiErrorMapper,
) : AuthRepository {

    override val isAuthenticated: Flow<Boolean> = tokenStore.hasToken

    /**
     * On success the token is stored *and* the profile cached, so Home renders a name on the
     * very first frame instead of flashing a placeholder.
     *
     * On failure nothing is written. A half-written session — profile cached, no token — would
     * make the app look signed in while every request 401s.
     */
    override suspend fun login(username: String, password: String): ApiResult<Unit> =
        errors.call { api.login(LoginRequest(username = username.trim(), password = password)) }
            .also { result ->
                if (result is ApiResult.Success) {
                    tokenStore.save(result.value.token)
                    dataStore.edit { prefs ->
                        prefs[PROFILE_ID] = result.value.user.id
                        prefs[PROFILE_NAME] = result.value.user.name
                        prefs[PROFILE_USERNAME] = result.value.user.username
                    }
                }
            }
            .map { }

    /**
     * The local session is cleared regardless of what the server says. A guard who is offline
     * must still be able to sign out, and a server that rejects the call has already forgotten
     * the token anyway.
     *
     * The attendance queue is deliberately untouched: unsynced records survive sign-out and
     * upload once someone authenticates again.
     */
    override suspend fun logout() {
        runCatching { api.logout() }
        tokenStore.clear()
        dataStore.edit { prefs ->
            prefs.remove(PROFILE_ID)
            prefs.remove(PROFILE_NAME)
            prefs.remove(PROFILE_USERNAME)
        }
    }

    private companion object {
        val PROFILE_ID = longPreferencesKey("profile_id")
        val PROFILE_NAME = stringPreferencesKey("profile_name")
        val PROFILE_USERNAME = stringPreferencesKey("profile_username")
    }
}
