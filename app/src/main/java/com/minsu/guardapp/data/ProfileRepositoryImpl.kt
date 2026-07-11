package com.minsu.guardapp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.map
import com.minsu.guardapp.domain.model.Announcement
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.domain.repository.AnnouncementRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The guard's own profile is cached so Home renders a name offline, and so the selfie
 * watermark can print it with no network.
 */
@Singleton
class DefaultProfileRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
) : ProfileRepository {

    override fun observe(): Flow<GuardProfile?> = dataStore.data.map { prefs ->
        val id = prefs[ID] ?: return@map null
        GuardProfile(
            id = id,
            name = prefs[NAME].orEmpty(),
            username = prefs[USERNAME].orEmpty(),
        )
    }

    override suspend fun refresh(): ApiResult<Unit> =
        errors.call { api.profile().data.user }
            .also { result ->
                if (result is ApiResult.Success) {
                    dataStore.edit { prefs ->
                        prefs[ID] = result.value.id
                        prefs[NAME] = result.value.name
                        prefs[USERNAME] = result.value.username
                    }
                }
            }
            .map { }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(ID); prefs.remove(NAME); prefs.remove(USERNAME)
        }
    }

    private companion object {
        val ID = longPreferencesKey("profile_id")
        val NAME = stringPreferencesKey("profile_name")
        val USERNAME = stringPreferencesKey("profile_username")
    }
}

/**
 * Announcements are advisory. They are held in memory rather than cached: a stale notice is
 * worse than none, and nothing in the attendance flow depends on them.
 */
@Singleton
class DefaultAnnouncementRepository @Inject constructor(
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
) : AnnouncementRepository {

    private val state = MutableStateFlow<List<Announcement>>(emptyList())

    override fun observe(): Flow<List<Announcement>> = state

    override suspend fun refresh(): ApiResult<Unit> =
        errors.call { api.announcements().data }
            .also { result ->
                if (result is ApiResult.Success) {
                    state.value = result.value.map { Announcement(it.id, it.title, it.content) }
                }
            }
            .map { }
}
