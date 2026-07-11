package com.appetiser.guardapp.core.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the one-time device onboarding has been completed.
 *
 * Device-level, not per-session: it is deliberately not cleared on sign-out, so a guard who
 * signs out and back in is not shown the intro again. It only reappears on a fresh install.
 */
interface OnboardingPreferences {
    val completed: Flow<Boolean>
    suspend fun markCompleted()
}

@Singleton
class DataStoreOnboardingPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : OnboardingPreferences {

    override val completed: Flow<Boolean> = dataStore.data.map { it[COMPLETED] ?: false }

    override suspend fun markCompleted() {
        dataStore.edit { it[COMPLETED] = true }
    }

    private companion object {
        val COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}
