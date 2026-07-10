package com.appetiser.guardapp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.appetiser.guardapp.domain.model.AppSettings
import com.appetiser.guardapp.domain.model.GpsFailurePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the last known `GET /api/settings`, so thresholds survive going offline. */
interface SettingsCache {
    fun observe(): Flow<AppSettings>
    suspend fun save(settings: AppSettings)
}

@Singleton
class DataStoreSettingsCache @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsCache {

    override fun observe(): Flow<AppSettings> = dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            gpsAccuracyThresholdMetres = prefs[ACCURACY] ?: defaults.gpsAccuracyThresholdMetres,
            gpsFailurePolicy = prefs[POLICY]?.let(GpsFailurePolicy::parse) ?: defaults.gpsFailurePolicy,
            gpsTimeoutSeconds = prefs[TIMEOUT] ?: defaults.gpsTimeoutSeconds,
            imageQuality = prefs[QUALITY] ?: defaults.imageQuality,
            imageMaxDimensionPx = prefs[MAX_DIMENSION] ?: defaults.imageMaxDimensionPx,
            maintenanceMessage = prefs[MAINTENANCE],
        )
    }

    override suspend fun save(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[ACCURACY] = settings.gpsAccuracyThresholdMetres
            prefs[POLICY] = settings.gpsFailurePolicy.name
            prefs[TIMEOUT] = settings.gpsTimeoutSeconds
            prefs[QUALITY] = settings.imageQuality
            prefs[MAX_DIMENSION] = settings.imageMaxDimensionPx
            settings.maintenanceMessage
                ?.let { prefs[MAINTENANCE] = it }
                ?: prefs.remove(MAINTENANCE)
        }
    }

    private companion object {
        val ACCURACY = floatPreferencesKey("gps_accuracy_threshold_m")
        val POLICY = stringPreferencesKey("gps_failure_policy")
        val TIMEOUT = intPreferencesKey("gps_timeout_seconds")
        val QUALITY = intPreferencesKey("image_quality")
        val MAX_DIMENSION = intPreferencesKey("image_max_dimension_px")
        val MAINTENANCE = stringPreferencesKey("maintenance_message")
    }
}
