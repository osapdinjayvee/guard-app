package com.minsu.guardapp.feature.scan

import android.Manifest
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minsu.guardapp.R
import com.minsu.guardapp.core.camera.QrAnalyzer
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.feature.selfie.SelfieScreen
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.PermissionGate
import com.minsu.guardapp.ui.theme.SyncFailed
import com.minsu.guardapp.ui.theme.SyncPending
import java.util.concurrent.Executors

@Composable
fun ScanQrScreen(viewModel: ScanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Capture takes over the whole screen: the scanner's camera must be released first, and
    // two bound camera use-cases would fight over the device.
    (state as? ScanState.ReadyToCapture)?.let { ready ->
        SelfieScreen(
            checkpoint = ready.checkpoint,
            type = ready.type,
            onCancel = viewModel::scanAgain,
        )
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            // Black, not the app background. The viewfinder is the screen, and a pale surround
            // behind a camera preview both looks broken and closes the guard's pupils in the dark.
            .background(Color.Black),
    ) {
        PermissionGate(
            permissions = listOf(Manifest.permission.CAMERA),
            iconRes = R.drawable.ic_nav_scan,
            title = "Camera access needed",
            rationale = "The camera reads the checkpoint QR code. It is used only while you are scanning.",
        ) {
            var camera by remember { mutableStateOf<Camera?>(null) }
            var torchOn by remember { mutableStateOf(false) }

            LaunchedEffect(torchOn, camera) {
                camera?.cameraControl?.enableTorch(torchOn)
            }

            CameraPreview(
                onCodeScanned = viewModel::onCodeScanned,
                onCameraReady = { camera = it },
                modifier = Modifier.fillMaxSize(),
            )

            ScannerOverlay(accent = MaterialTheme.colorScheme.primary)

            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Scan QR",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                // A checkpoint at a perimeter post at 2am is not lit. Without a torch the scanner
                // is decorative for half of every shift.
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    TorchButton(on = torchOn, onToggle = { torchOn = !torchOn })
                }
            }

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                when (val current = state) {
                    ScanState.Scanning -> Hint("Line the QR code up inside the frame")

                    ScanState.Resolving -> Hint("Checking that checkpoint…", busy = true)

                    is ScanState.ChoosingType -> TypeChoice(
                        checkpointName = current.checkpoint.name,
                        checkpointCode = current.checkpoint.code,
                        allowedTypes = current.allowedTypes,
                        onChoose = viewModel::onTypeChosen,
                        onCancel = viewModel::scanAgain,
                    )

                    is ScanState.ReadyToCapture -> Unit // handled above, before the camera binds

                    is ScanState.Disabled -> Result(
                        title = "Checkpoint retired",
                        body = "${current.checkpoint.code} is no longer in use. Try another checkpoint.",
                        colour = SyncPending,
                        onDismiss = viewModel::scanAgain,
                    )

                    is ScanState.Unknown -> Result(
                        title = "Unrecognised code",
                        body = "\"${current.code}\" is not a checkpoint in this system. " +
                            "Check you are scanning a GuardApp checkpoint sticker.",
                        colour = SyncFailed,
                        onDismiss = viewModel::scanAgain,
                    )

                    // Deliberately not worded as a bad code. We could not check, and saying
                    // otherwise would send the guard hunting for a door that is right in front
                    // of them.
                    is ScanState.Unverifiable -> Result(
                        title = "Can't check right now",
                        body = "\"${current.code}\" isn't saved on this phone yet, and there's no " +
                            "connection to look it up. Open Home while online to download the " +
                            "checkpoints, then scan again.",
                        colour = SyncPending,
                        onDismiss = viewModel::scanAgain,
                    )

                    // A stationed shift ends where it began. Said here, at the wrong door, while the
                    // guard can still walk to the right one — rather than by a rejection that lands
                    // after the shift is over.
                    is ScanState.WrongPost -> Result(
                        title = "Not your post today",
                        body = "You timed in at ${current.timedInAt}. A stationed shift ends where " +
                            "it began, so scan ${current.timedInAt} to time out — not " +
                            "${current.scanned.code}.",
                        colour = SyncFailed,
                        onDismiss = viewModel::scanAgain,
                    )

                    // A rest day is not an error, and is not worded as one.
                    ScanState.NotScheduledToday -> Result(
                        title = "No shift today",
                        body = "You are not on the duty roster today, so there is no attendance to " +
                            "record. If that looks wrong, check with the office.",
                        colour = SyncPending,
                        onDismiss = viewModel::scanAgain,
                    )

                    // Not the guard's fault, and not something they can fix by scanning again.
                    ScanState.NotOnRoster -> Result(
                        title = "Account not on the roster",
                        body = "Your login has not been connected to a guard on the duty roster, so " +
                            "the app cannot tell what you are scheduled for. Ask the office to link " +
                            "your account.",
                        colour = SyncFailed,
                        onDismiss = viewModel::scanAgain,
                    )
                }
            }
        }
    }
}

@Composable
private fun TorchButton(on: Boolean, onToggle: () -> Unit) {
    IconButton(
        onClick = onToggle,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.18f),
            contentColor = Color.White,
        ),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_nav_scan),
            contentDescription = if (on) "Turn the torch off" else "Turn the torch on",
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Guidance, floated over the viewfinder. Never a card: it must not compete with the reticle. */
@Composable
private fun Hint(text: String, busy: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.Black.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Spacer(Modifier.size(10.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Time In / Time Out. Two large targets rather than a dropdown: a guard taps this with gloves
 * on, in the dark, and a mis-tap records the wrong half of a shift.
 */
@Composable
private fun TypeChoice(
    checkpointName: String,
    checkpointCode: String,
    allowedTypes: List<AttendanceType>,
    onChoose: (AttendanceType) -> Unit,
    onCancel: () -> Unit,
) {
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    painter = painterResource(R.drawable.ic_locator),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.padding(9.dp).size(20.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    checkpointName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    checkpointCode,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Only what the roster permits. A stationed guard is never shown a patrol visit — they are
        // at one post, and there is nothing to visit. Offering a button the server will reject is a
        // trap, not a choice.
        //
        // Stacked, not in a row. Sharing the width between three buttons left "Checkpoint" too
        // narrow to fit: it wrapped to two lines inside a fixed-height button and came out taller
        // and misshapen next to the others. Full-width rows fit any label at any font scale, and a
        // gloved thumb in the dark wants the bigger target anyway.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            allowedTypes.forEachIndexed { index, type ->
                TypeButton(
                    label = type.label(),
                    filled = index == 0,
                    onClick = { onChoose(type) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Scan a different checkpoint", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TypeButton(label: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (filled) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = modifier.height(54.dp),
        ) { Text(label, fontWeight = FontWeight.Bold, maxLines = 1) }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            modifier = modifier.height(54.dp),
        ) {
            Text(
                label,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Result(title: String, body: String, colour: Color, onDismiss: () -> Unit) {
    GuardCard {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = colour,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("Scan again", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CameraPreview(
    onCodeScanned: (String) -> Unit,
    onCameraReady: (Camera) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Analysis runs off the main thread; a single thread is enough and keeps ordering simple.
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        // Dropping stale frames keeps the preview responsive; a QR code stays
                        // in view long enough that no frame is worth queueing for.
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, QrAnalyzer(onCodeScanned)) }

                    provider.unbindAll()
                    onCameraReady(
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    )
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun AttendanceType.label(): String = when (this) {
    AttendanceType.TIME_IN -> "Time In"
    AttendanceType.TIME_OUT -> "Time Out"
    AttendanceType.CHECKPOINT -> "Checkpoint"
}
