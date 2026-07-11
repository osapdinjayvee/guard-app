package com.appetiser.guardapp.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app lock is turned on, and whether the guard has been offered the choice yet.
 *
 * The lock is **opt-in**: it defaults off, so a fresh install signs in with email and password
 * and nothing more. The guard is prompted once after the first login to enable it, and can flip
 * it any time from the Account screen.
 */
interface LockPreferences {
    val enabled: Flow<Boolean>

    /** False until the guard has either enabled or skipped the lock. Drives the one-time prompt. */
    val setupSeen: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
    suspend fun markSetupSeen()

    /** Reset on sign-out so the next guard on a shared device is offered the choice again. */
    suspend fun clear()
}

@Singleton
class DataStoreLockPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : LockPreferences {

    override val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: false }
    override val setupSeen: Flow<Boolean> = dataStore.data.map { it[SETUP_SEEN] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    override suspend fun markSetupSeen() {
        dataStore.edit { it[SETUP_SEEN] = true }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(ENABLED); it.remove(SETUP_SEEN) }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("app_lock_enabled")
        val SETUP_SEEN = booleanPreferencesKey("app_lock_setup_seen")
    }
}
