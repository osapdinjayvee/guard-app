package com.minsu.guardapp.feature.reference

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
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
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.ui.components.PermissionGate
import com.minsu.guardapp.ui.components.ScreenTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Streams the guard's live location for the checkpoint map. Starts and stops with the map's presence. */
@HiltViewModel
class CheckpointMapViewModel @Inject constructor(
    private val location: LocationProvider,
) : ViewModel() {

    private val _fix = MutableStateFlow<LocationFix?>(null)
    val fix: StateFlow<LocationFix?> = _fix.asStateFlow()

    private val jobs = mutableListOf<Job>()

    fun start() {
        if (jobs.any { it.isActive }) return
        jobs += viewModelScope.launch {
            location.lastKnownFix()?.let { seed -> if (_fix.value == null) _fix.value = seed }
        }
        jobs += viewModelScope.launch { location.stream().collect { _fix.value = it } }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}

/**
 * A full-screen map for one checkpoint: the post as a red pin, the guard's live position as an azure
 * pin. It fills the round screen's content, so the bottom navigation stays put — this is a detail of
 * the round, not a place the guard leaves the app's shell to reach.
 */
@Composable
fun CheckpointMapScreen(
    checkpoint: Checkpoint,
    onBack: () -> Unit,
    viewModel: CheckpointMapViewModel = hiltViewModel(),
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ScreenTitle(checkpoint.name, onBack = onBack)

        val lat = checkpoint.latitude
        val lng = checkpoint.longitude
        if (lat == null || lng == null) {
            // A checkpoint an admin never placed on the map cannot be pinned. Said plainly rather
            // than shown as an empty map centred on the ocean off Africa (0,0).
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "${checkpoint.code} has no location set, so it cannot be shown on the map.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        PermissionGate(
            permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            iconRes = R.drawable.ic_locator,
            title = "Location needed for the map",
            rationale = "The map shows this checkpoint and where you are right now.",
        ) {
            val fix by viewModel.fix.collectAsStateWithLifecycle()
            DisposableEffect(Unit) {
                viewModel.start()
                onDispose { viewModel.stop() }
            }
            CheckpointMap(checkpointName = checkpoint.name, code = checkpoint.code, lat = lat, lng = lng, fix = fix)
        }
    }
}

@Composable
private fun CheckpointMap(checkpointName: String, code: String, lat: Double, lng: Double, fix: LocationFix?) {
    val checkpoint = LatLng(lat, lng)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(checkpoint, 17f)
    }

    // Once a fix lands, frame both the post and the guard so a guard standing away from the
    // checkpoint still sees both pins and the gap between them.
    LaunchedEffect(fix) {
        val you = fix ?: return@LaunchedEffect
        val bounds = LatLngBounds.builder()
            .include(checkpoint)
            .include(LatLng(you.latitude, you.longitude))
            .build()
        runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 180)) }
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = MapUiSettings(
                compassEnabled = true,
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false,
                zoomControlsEnabled = true,
            ),
            properties = MapProperties(mapType = MapType.NORMAL),
        ) {
            // The post — the default red pin.
            Marker(
                state = remember(checkpoint) { MarkerState(position = checkpoint) },
                title = checkpointName,
                snippet = code,
            )
            // The guard — an azure pin, so the two are never confused for one another.
            if (fix != null) {
                val you = LatLng(fix.latitude, fix.longitude)
                val youState = remember { MarkerState(position = you) }
                LaunchedEffect(you) { youState.position = you }
                Marker(
                    state = youState,
                    title = "You are here",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
                )
            }
        }

        // How far the guard is from the post, spelled out — the same figure the geofence rule uses.
        fix?.let {
            val metres = haversineMetres(it.latitude, it.longitude, lat, lng)
            Text(
                "${formatDistance(metres)} from $code",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private fun formatDistance(metres: Float): String =
    if (metres >= 1_000f) "%.1f km".format(metres / 1_000f) else "${metres.toInt()} m"

/** Great-circle distance in metres. Mirrors the capture screen's check, so the two agree. */
private fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return (earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))).toFloat()
}
