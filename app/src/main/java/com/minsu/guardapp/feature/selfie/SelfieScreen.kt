package com.minsu.guardapp.feature.selfie

import android.Manifest
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minsu.guardapp.R
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.PermissionGate
import com.minsu.guardapp.ui.components.ScreenTitle
import com.minsu.guardapp.ui.theme.SyncFailed
import com.minsu.guardapp.ui.theme.SyncPending
import java.util.concurrent.Executors

@Composable
fun SelfieScreen(
    checkpoint: Checkpoint,
    type: AttendanceType,
    onCancel: () -> Unit,
    viewModel: SelfieViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(checkpoint.id, type) { viewModel.start(checkpoint, type) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        ScreenTitle("Attendance photo")

        PermissionGate(
            permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION),
            iconRes = R.drawable.ic_locator,
            title = "Camera and location needed",
            rationale = "The photo proves you were at the checkpoint, and the location is recorded with it.",
        ) {
            val captured = state.capturedFile
            when {
                state.submitted -> SubmittedConfirmation(onDone = onCancel)
                captured != null -> CapturedPreview(
                    path = captured.absolutePath,
                    state = state,
                    onRetake = viewModel::retake,
                    onAcknowledge = viewModel::setDutiesAcknowledged,
                    onSubmit = viewModel::submit,
                )
                else -> LiveCapture(state, viewModel, onCancel)
            }
        }
    }
}

@Composable
private fun LiveCapture(state: SelfieUiState, viewModel: SelfieViewModel, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(24.dp)),
        ) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            imageCapture,
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )

            // The same lines that get burned into the file, shown live so the guard can see
            // what will be recorded. The overlay is cosmetic; the file is the evidence.
            MetadataOverlay(
                lines = state.overlayLines,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        Spacer(Modifier.height(16.dp))

        state.gpsBlock?.let { GpsNotice(it, state.settings.gpsAccuracyThresholdMetres) }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { viewModel.capture(imageCapture, executor) },
            enabled = state.canCapture,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (state.isCapturing) {
                CircularProgressIndicator(
                    Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Capture", fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MetadataOverlay(lines: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun GpsNotice(block: GpsBlock, thresholdMetres: Float) {
    val (title, body, colour) = when (block) {
        GpsBlock.WAITING -> Triple("Getting your location…", "Hold still for a moment.", SyncPending)
        GpsBlock.NO_FIX -> Triple(
            "No GPS fix",
            "Attendance cannot be recorded without a location. Step outside and try again.",
            SyncFailed,
        )
        GpsBlock.TOO_INACCURATE -> Triple(
            "Location is not precise enough",
            "Waiting for a fix within ±${thresholdMetres.toInt()} m.",
            SyncPending,
        )
    }
    GuardCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colour)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CapturedPreview(
    path: String,
    state: SelfieUiState,
    onRetake: () -> Unit,
    onAcknowledge: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Decoded directly rather than pulling in an image-loading library for one file.
        val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
        if (bitmap == null) {
            Text("The photo could not be read.", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        Image(
            bitmap = bitmap,
            contentDescription = "Captured attendance photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(24.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The date, time, location and checkpoint are part of this image.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))
        DutiesGate(
            duty = state.duty,
            acknowledged = state.dutiesAcknowledged,
            onAcknowledge = onAcknowledge,
        )

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onRetake,
                enabled = !state.isSubmitting,
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                Text("Retake", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Submit", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The Duties & Responsibilities acknowledgement gate (PRD §6). The checkbox drives
 * [SelfieUiState.canSubmit]; the record cannot be committed until it is confirmed.
 */
@Composable
private fun DutiesGate(duty: Duty?, acknowledged: Boolean, onAcknowledge: (Boolean) -> Unit) {
    GuardCard {
        Text(
            duty?.title ?: "Duties & Responsibilities",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            duty?.content ?: "Duties are not available offline yet. They will sync when you are online.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = duty != null) { onAcknowledge(!acknowledged) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = acknowledged,
                onCheckedChange = { onAcknowledge(it) },
                enabled = duty != null,
            )
            Text(
                "I have read and understood my duties and responsibilities.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SubmittedConfirmation(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Attendance recorded",
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
        Button(
            onClick = onDone,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text("Done", fontWeight = FontWeight.Bold)
        }
    }
}
