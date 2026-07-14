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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

                // The acknowledgement is a screen of its own, reached only once the photo is
                // accepted. Three decisions on one scroll — is the photo good, have I read the
                // duties, do I submit — is how a guard ticks a box they never read.
                captured != null && state.onEvaluationStep -> ShiftEvaluation(
                    state = state,
                    onAnswer = viewModel::answer,
                    onBack = viewModel::previousQuestion,
                    onBackToPhoto = viewModel::backToPhoto,
                    onSubmit = viewModel::submit,
                )

                captured != null -> CapturedPreview(
                    path = captured.absolutePath,
                    onRetake = viewModel::retake,
                    onContinue = viewModel::proceedToEvaluation,
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

        state.gpsBlock?.let { GpsNotice(it, state) }
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
private fun GpsNotice(block: GpsBlock, state: SelfieUiState) {
    val (title, body, colour) = when (block) {
        GpsBlock.WAITING -> Triple("Getting your location…", "Hold still for a moment.", SyncPending)
        GpsBlock.NO_FIX -> Triple(
            "No GPS fix",
            "Attendance cannot be recorded without a location. Step outside and try again.",
            SyncFailed,
        )
        GpsBlock.TOO_INACCURATE -> Triple(
            "Location is not precise enough",
            "Waiting for a fix within ±${state.settings.gpsAccuracyThresholdMetres.toInt()} m. " +
                "Currently ±${state.fix?.accuracyMetres?.toInt() ?: "—"} m.",
            SyncPending,
        )
        // Said here, before the shutter, rather than by the server hours later. The distance is
        // spelled out because "too far" is not actionable and "8.8 km away" is: it tells the guard
        // immediately whether they are at the wrong post or the post has the wrong coordinates.
        GpsBlock.OUT_OF_RANGE -> Triple(
            "Too far from ${state.checkpoint?.code ?: "this checkpoint"}",
            "You are ${formatDistance(state.distanceToCheckpointMetres)} away, and the limit is " +
                "${state.settings.geofenceRadiusMetres.toInt()} m. Move closer to the checkpoint, " +
                "or scan the one you are actually at.",
            SyncFailed,
        )
    }
    GuardCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colour)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatDistance(metres: Float?): String = when {
    metres == null -> "an unknown distance"
    metres >= 1_000f -> "%.1f km".format(metres / 1_000f)
    else -> "${metres.toInt()} m"
}

/** Step 1 after the shutter: is this photo good enough? Nothing else competes with that. */
@Composable
private fun CapturedPreview(path: String, onRetake: () -> Unit, onContinue: () -> Unit) {
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

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                Text("Retake", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onContinue,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                Text("Continue", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Step 2, on a Time Out: the post-shift self-evaluation.
 *
 * One question at a time, Yes or No, two large targets. A guard answers this at the end of a twelve
 * hour shift, tired and wanting to go home, and a seven-item form on one screen collects a column of
 * identical taps that is evidence of nothing. One question filling the screen is one decision.
 *
 * A Time In and a roving guard's checkpoint visits never see this: an evaluation describes a shift
 * that has *ended*.
 */
@Composable
private fun ShiftEvaluation(
    state: SelfieUiState,
    onAnswer: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    onBackToPhoto: () -> Unit,
    onSubmit: () -> Unit,
) {
    val question = state.currentQuestion

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
    ) {
        /*
         * No questions on the phone, and a Time Out cannot be closed without them.
         *
         * Said out loud, with the reason and the fix. The alternative — and what the app used to do —
         * was to decide that no questions meant no evaluation, submit, and let the server reject the
         * record hours later. The guard would have had a shift they could not close and no idea why.
         */
        if (state.missingQuestions) {
            GuardCard {
                Text(
                    "Shift questions not downloaded",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "A Time Out has to carry your end-of-shift answers, and this phone has not " +
                        "downloaded the questions yet. Connect to the internet once and open the " +
                        "app — then time out again. Your photo is not lost; scan again when you " +
                        "are back online.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(
            "End of shift",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (question != null) {
                "Question ${state.answeredCount + 1} of ${state.questions.size}"
            } else {
                "All ${state.questions.size} answered"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = {
                if (state.questions.isEmpty()) 1f
                else state.answeredCount.toFloat() / state.questions.size
            },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(20.dp))

        if (question != null) {
            GuardCard {
                Text(
                    question.question,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                // Yes and No are equals. Styling one as the primary action would tell a tired guard
                // which answer the app expects, and they would give it.
                OutlinedButton(
                    onClick = { onAnswer(question.id, false) },
                    modifier = Modifier.weight(1f).height(64.dp),
                ) {
                    Text("No", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(
                    onClick = { onAnswer(question.id, true) },
                    modifier = Modifier.weight(1f).height(64.dp),
                ) {
                    Text("Yes", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (state.answeredCount > 0) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to the previous question", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // Every answer, shown back before it is committed. A guard has just made seven decisions
            // one screen at a time, and this is the only place they can see what they actually said.
            GuardCard {
                state.questions.forEach { q ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            q.question,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (state.answers[q.id] == true) "Yes" else "No",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !state.isSubmitting,
                    modifier = Modifier.weight(1f).height(54.dp),
                ) {
                    Text("Change last", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
        }

        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onBackToPhoto, modifier = Modifier.fillMaxWidth()) {
            Text("Back to the photo", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
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
