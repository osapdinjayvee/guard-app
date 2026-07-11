package com.minsu.guardapp.core.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A single GPS fix. `accuracyMetres` is the radius of 68% confidence, as Android reports it. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val timeMillis: Long,
)

interface LocationProvider {
    /**
     * A fresh fix, or null if none arrives within [timeoutMillis].
     *
     * Null is a meaningful answer, not an error: the GPS failure policy from
     * `GET /api/settings` decides whether the guard may then submit.
     */
    suspend fun currentFix(timeoutMillis: Long): LocationFix?
}

@Singleton
class FusedLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationProvider {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    /**
     * `getCurrentLocation`, not `lastLocation`: a cached fix could be hours old and kilometres
     * away, which is exactly the fraud the attendance photo exists to prevent.
     */
    @SuppressLint("MissingPermission") // The caller is behind PermissionGate(ACCESS_FINE_LOCATION).
    override suspend fun currentFix(timeoutMillis: Long): LocationFix? =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val cancellation = CancellationTokenSource()

                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                    .addOnSuccessListener { location ->
                        continuation.resume(
                            location?.let {
                                LocationFix(
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                    accuracyMetres = it.accuracy,
                                    timeMillis = it.time,
                                )
                            }
                        )
                    }
                    .addOnFailureListener { continuation.resume(null) }

                // Stop the GPS chip when the guard leaves the screen mid-acquisition.
                continuation.invokeOnCancellation { cancellation.cancel() }
            }
        }
}
