package com.minsu.guardapp.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

    /**
     * A live stream of fixes for as long as it is collected.
     *
     * The overlay burned into a selfie has to show where the guard *is*, and a single fix taken
     * when the screen opened cannot do that: the first fix off a cold GPS chip is routinely
     * hundreds of metres out and sharpens over the following seconds. Worse, a one-shot attempt
     * that times out never tries again, so a guard who walks out of a stairwell into open sky is
     * still told their location is unavailable — with the camera pointed at their face and no way
     * forward.
     *
     * Streaming makes the accuracy figure on screen mean something: it visibly tightens, and the
     * guard can see when it is good enough to shoot.
     */
    fun stream(intervalMillis: Long = 1_000L): Flow<LocationFix>
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

    /**
     * Updates stop the moment the flow stops being collected — `awaitClose` removes the callback —
     * so the GPS chip is never left running behind a screen the guard has walked away from.
     */
    @SuppressLint("MissingPermission") // The caller is behind PermissionGate(ACCESS_FINE_LOCATION).
    override fun stream(intervalMillis: Long): Flow<LocationFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(
                        LocationFix(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMetres = location.accuracy,
                            timeMillis = location.time,
                        )
                    )
                }
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose { client.removeLocationUpdates(callback) }
    }
}
