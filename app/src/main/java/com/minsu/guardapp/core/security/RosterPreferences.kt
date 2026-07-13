package com.minsu.guardapp.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this login is joined to a guard on the duty roster.
 *
 * A one-bit fact that is worth keeping, because without it two very different situations look
 * identical to a guard: "you have no shift today" and "nobody has connected your account to the
 * roster, so the app can never find your shift". The first is a rest day. The second is an admin
 * error that will keep them from working, and it does not fix itself.
 */
interface RosterPreferences {
    val linked: Flow<Boolean>

    suspend fun setLinked(linked: Boolean)

    /** Cleared on sign-out: the next guard on a shared device is a different person entirely. */
    suspend fun clear()
}

@Singleton
class DataStoreRosterPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : RosterPreferences {

    // Defaults to true. An app that has never fetched the roster must not accuse the office of
    // failing to link an account it has not yet asked about.
    override val linked: Flow<Boolean> = dataStore.data.map { it[LINKED] ?: true }

    override suspend fun setLinked(linked: Boolean) {
        dataStore.edit { it[LINKED] = linked }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(LINKED) }
    }

    private companion object {
        val LINKED = booleanPreferencesKey("roster_linked")
    }
}
