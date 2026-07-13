package com.minsu.guardapp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.AnnouncementDao
import com.minsu.guardapp.core.database.AnnouncementEntity
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
 * Announcements, cached on the phone like everything else.
 *
 * They were held in memory, on the reasoning that they are advisory and a stale notice is worse than
 * none. Both halves of that turned out to be wrong. An announcement is the office telling a guard
 * something they need on shift — a gate closed, a route changed — and the moment they most need to
 * re-read it is standing at a post with no signal, which is exactly when the process has been killed
 * and the memory is empty. The profile, the checkpoints, the duties and the roster all survive that;
 * this was the one thing that did not.
 *
 * "Stale beats absent" is the same trade the rest of the app already makes: a failed refresh leaves
 * what is there alone rather than blanking the screen. A withdrawn announcement does disappear —
 * the table is replaced wholesale, not merged — so a notice the office pulled will not linger.
 */
@Singleton
class DefaultAnnouncementRepository @Inject constructor(
    private val dao: AnnouncementDao,
    private val api: GuardApi,
    private val errors: ApiErrorMapper,
    private val clock: Clock,
) : AnnouncementRepository {

    override fun observe(): Flow<List<Announcement>> =
        dao.observeAll().map { rows -> rows.map { Announcement(it.id, it.title, it.content) } }

    override suspend fun refresh(): ApiResult<Unit> =
        errors.call { api.announcements().data }
            .also { result ->
                if (result is ApiResult.Success) {
                    val now = clock.nowMillis()
                    dao.clear()
                    dao.upsertAll(
                        result.value.map {
                            AnnouncementEntity(
                                id = it.id,
                                title = it.title,
                                content = it.content,
                                updatedAt = now,
                            )
                        }
                    )
                }
            }
            .map { }
}
