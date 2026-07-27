package com.minsu.guardapp.core.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the device remembers between update checks.
 *
 * Device-level and deliberately not cleared on sign-out: which build is installed has nothing to
 * do with who is signed in, and a shared handset should not re-nag the next guard about a version
 * the first one already declined.
 */
interface UpdatePreferences {
    /** The highest versionCode the guard has said "Later" to. 0 when they never have. */
    val dismissedVersionCode: Flow<Int>

    suspend fun dismiss(versionCode: Int)

    /** Epoch millis of the last completed automatic check, or 0. */
    suspend fun lastCheckedAt(): Long

    suspend fun markChecked(atMillis: Long)
}

@Singleton
class DataStoreUpdatePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UpdatePreferences {

    override val dismissedVersionCode: Flow<Int> =
        dataStore.data.map { it[DISMISSED] ?: 0 }

    /**
     * Kept as a maximum rather than an assignment. Dismissals only ever move forward, so a stale
     * manifest — a rollback on the server, or a cached response — cannot un-dismiss a version the
     * guard already declined and start the prompt appearing again.
     */
    override suspend fun dismiss(versionCode: Int) {
        dataStore.edit { it[DISMISSED] = maxOf(it[DISMISSED] ?: 0, versionCode) }
    }

    override suspend fun lastCheckedAt(): Long = dataStore.data.first()[LAST_CHECKED] ?: 0L

    override suspend fun markChecked(atMillis: Long) {
        dataStore.edit { it[LAST_CHECKED] = atMillis }
    }

    private companion object {
        val DISMISSED = intPreferencesKey("update_dismissed_version_code")
        val LAST_CHECKED = longPreferencesKey("update_last_checked_at")
    }
}
