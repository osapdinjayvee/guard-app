package com.minsu.guardapp.feature.observation

import android.Manifest
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minsu.guardapp.R
import com.minsu.guardapp.domain.model.Observation
import com.minsu.guardapp.domain.model.ObservationRules
import com.minsu.guardapp.domain.model.SyncState
import com.minsu.guardapp.feature.home.SyncBadge
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.PermissionGate
import com.minsu.guardapp.ui.components.ScreenTitle
import com.minsu.guardapp.ui.theme.SyncFailed
import com.minsu.guardapp.ui.theme.SyncPending
import java.util.concurrent.Executors

// ---------------------------------------------------------------------------------------------
// The list

@Composable
fun ObservationsScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    viewModel: ObservationsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Observations", onBack = onBack)

        PrimaryButton("New observation", onClick = onNew)

        if (items.isEmpty()) {
            GuardCard {
                Text(
                    "No observations yet",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Report anything out of place on your round — a broken window, a gate left open — " +
                        "with a note and up to ${ObservationRules.MAX_PHOTOS} photos. It saves on this " +
                        "phone and uploads when you have a signal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items.forEach { observation ->
            ObservationCard(observation, onRetry = { viewModel.retry(observation.id) })
        }
    }
}

@Composable
private fun ObservationCard(observation: Observation, onRetry: () -> Unit) {
    GuardCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    dateTime(observation.capturedAtMillis),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    observation.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${observation.photoPaths.size} photo(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            SyncBadge(observation.syncState)
        }

        // A report the office never received is never silent.
        if (observation.syncState == SyncState.FAILED || observation.syncState == SyncState.REJECTED) {
            observation.lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = SyncFailed)
            }
            TextButton(onClick = onRetry) {
                Text("Retry upload", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Writing one

@Composable
fun NewObservationScreen(
    onDone: () -> Unit,
    viewModel: ObservationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Back from the camera returns to the form, not out of the screen with the note unsaved.
    BackHandler(enabled = state.isCameraOpen) { viewModel.closeCamera() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        ScreenTitle(
            if (state.isCameraOpen) "Observation photo" else "New observation",
            onBack = if (state.isCameraOpen) viewModel::closeCamera else onDone,
        )

        PermissionGate(
            permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION),
            iconRes = R.drawable.ic_report,
            title = "Camera and location needed",
            rationale = "Each photo is stamped with where and when it was taken, and the office needs both.",
        ) {
            LaunchedEffect(Unit) { viewModel.start() }

            when {
                state.submitted -> Saved(onDone)
                state.isCameraOpen -> ObservationCamera(state, viewModel)
                else -> ObservationForm(state, viewModel)
            }
        }
    }
}

@Composable
private fun ObservationForm(state: ObservationUiState, viewModel: ObservationViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::onNoteChange,
            label = { Text("What did you observe?") },
            placeholder = { Text("e.g. Broken window near the main gate") },
            minLines = 5,
            enabled = !state.isSubmitting,
            supportingText = {
                Text("${state.note.length} / ${ObservationRules.MAX_NOTE_LENGTH}")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "Photos (${state.photos.size} of ${ObservationRules.MAX_PHOTOS})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (state.photos.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.photos.forEachIndexed { index, photo ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Thumbnail(photo.file.absolutePath)
                        TextButton(onClick = { viewModel.removePhoto(index) }, enabled = !state.isSubmitting) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        } else {
            Text(
                "At least one photo is required.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = viewModel::openCamera,
            enabled = state.canAddPhoto,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                if (state.photos.size >= ObservationRules.MAX_PHOTOS) "Photo limit reached" else "Add photo",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        PrimaryButton(
            "Submit observation",
            onClick = viewModel::submit,
            enabled = state.canSubmit,
            busy = state.isSubmitting,
        )
    }
}

@Composable
private fun Thumbnail(path: String) {
    // Sampled down: five full-size photos decoded for a row of thumbnails is how a cheap phone OOMs.
    val bitmap = remember(path) {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
    }
    Box(
        Modifier
            .size(110.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Observation photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ObservationCamera(state: ObservationUiState, viewModel: ObservationViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(24.dp)),
        ) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        provider.unbindAll()
                        // The back camera: the subject is what the guard is looking at, not the guard.
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.overlayLines.forEach {
                    Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        state.gpsBlock?.let { GpsNotice(it, state) }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            "Capture",
            onClick = { viewModel.capture(imageCapture, executor) },
            enabled = state.canCapture,
            busy = state.isCapturing,
        )
        OutlinedButton(onClick = viewModel::closeCamera, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GpsNotice(block: ObservationGpsBlock, state: ObservationUiState) {
    val (title, body, colour) = when (block) {
        ObservationGpsBlock.WAITING -> Triple("Getting your location…", "Hold still for a moment.", SyncPending)
        ObservationGpsBlock.NO_FIX -> Triple(
            "No GPS fix",
            "Every observation photo needs a location. Step outside and try again.",
            SyncFailed,
        )
        ObservationGpsBlock.TOO_INACCURATE -> Triple(
            "Location is not precise enough",
            "Waiting for a fix within ±${state.settings.gpsAccuracyThresholdMetres.toInt()} m. " +
                "Currently ±${state.fix?.accuracyMetres?.toInt() ?: "—"} m.",
            SyncPending,
        )
    }
    GuardCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colour)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Saved(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Observation saved",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Saved on this device. It uploads automatically when you are online.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Done", onClick = onDone)
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}
