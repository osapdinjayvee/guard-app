package com.minsu.guardapp.feature.home

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.minsu.guardapp.R
import com.minsu.guardapp.core.location.LocationFix
import com.minsu.guardapp.core.location.LocationProvider
import com.minsu.guardapp.ui.components.PermissionGate
import com.minsu.guardapp.ui.components.SectionHeading
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Feeds the Home map its location. Held in its own ViewModel — not [HomeViewModel] — so the GPS
 * stream lives and dies with the map's presence on screen, and Home's preview stays free of a
 * hilt-injected dependency.
 */
@HiltViewModel
class HomeLocationMapViewModel @Inject constructor(
    private val location: LocationProvider,
) : ViewModel() {

    private val _fix = MutableStateFlow<LocationFix?>(null)
    val fix: StateFlow<LocationFix?> = _fix.asStateFlow()

    private val jobs = mutableListOf<Job>()

    /** Begin updates. Seeds from the last known fix so the map is not blank while a fresh one locks. */
    fun start() {
        if (jobs.any { it.isActive }) return
        jobs += viewModelScope.launch {
            location.lastKnownFix()?.let { seed -> if (_fix.value == null) _fix.value = seed }
        }
        jobs += viewModelScope.launch {
            location.stream().collect { _fix.value = it }
        }
    }

    /** Stop the GPS chip the moment the map leaves the screen. */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}

/**
 * A compact, non-interactive map on Home showing where the guard is right now.
 *
 * Static by design: all gestures are off and the camera simply follows the fix. It is a glance,
 * not a tool — the offline-first app does not want a guard panning a tile map at a dead-spot post,
 * and a still frame keeps tile fetches and battery down.
 */
@Composable
fun HomeLocationMapSection(viewModel: HomeLocationMapViewModel = hiltViewModel()) {
    SectionHeading("Your location")
    PermissionGate(
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
        iconRes = R.drawable.ic_locator,
        title = "Location needed for the map",
        rationale = "The map shows where you are right now.",
    ) {
        val fix by viewModel.fix.collectAsStateWithLifecycle()
        DisposableEffect(Unit) {
            viewModel.start()
            onDispose { viewModel.stop() }
        }
        LocationMap(fix)
    }
}

@Composable
private fun LocationMap(fix: LocationFix?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (fix == null) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(
                    "Locating…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Box
        }

        val latLng = LatLng(fix.latitude, fix.longitude)
        val cameraPositionState = rememberCameraPositionState()
        // Follow the fix as it tightens, rather than freezing on the first, coarse reading.
        LaunchedEffect(latLng) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(latLng, 16f)
        }
        val markerState = remember { MarkerState(position = latLng) }
        LaunchedEffect(latLng) { markerState.position = latLng }

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            // A glance, not a tool: every gesture and control is off.
            uiSettings = MapUiSettings(
                compassEnabled = false,
                indoorLevelPickerEnabled = false,
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false,
                rotationGesturesEnabled = false,
                scrollGesturesEnabled = false,
                scrollGesturesEnabledDuringRotateOrZoom = false,
                tiltGesturesEnabled = false,
                zoomControlsEnabled = false,
                zoomGesturesEnabled = false,
            ),
            properties = MapProperties(mapType = MapType.NORMAL),
        ) {
            Marker(state = markerState, title = "You are here")
        }

        // The coordinates and accuracy, in the same words the selfie overlay uses, so a guard can
        // sanity-check the pin against the ±metres the attendance rules care about.
        Text(
            "±${fix.accuracyMetres.toInt()} m · ${"%.5f".format(fix.latitude)}, ${"%.5f".format(fix.longitude)}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
