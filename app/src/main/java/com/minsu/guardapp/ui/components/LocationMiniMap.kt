package com.minsu.guardapp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * A small, non-interactive Google Map pinned on one point.
 *
 * Static by design — every gesture and control is off — so it drops into an overlay (over a camera
 * preview, inside a card) as a glanceable "you are here" without stealing touches from what it sits
 * on. The camera follows the point as it moves, so a tightening GPS fix re-centres it.
 */
@Composable
fun LocationMiniMap(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
    zoom: Float = 16f,
) {
    val latLng = LatLng(latitude, longitude)
    val cameraPositionState = rememberCameraPositionState()
    LaunchedEffect(latLng) {
        cameraPositionState.position = CameraPosition.fromLatLngZoom(latLng, zoom)
    }
    val markerState = remember { MarkerState(position = latLng) }
    LaunchedEffect(latLng) { markerState.position = latLng }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
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
        Marker(state = markerState)
    }
}
