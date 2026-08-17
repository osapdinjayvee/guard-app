package com.minsu.guardapp.feature.scan

import android.Manifest
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.TimeUnit
import com.minsu.guardapp.R
import com.minsu.guardapp.core.camera.QrAnalyzer
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.feature.selfie.SelfieScreen
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.PermissionGate
import com.minsu.guardapp.ui.theme.SyncFailed
import com.minsu.guardapp.ui.theme.SyncPending
import com.minsu.guardapp.ui.theme.SyncSynced
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

            // Binding use cases directly sets up no focus-metering of its own, and a handset that
            // parks at a fixed focus until something asks it to hunt serves ML Kit a blurred code
            // forever — in frame, in focus to the eye, never decoded. Re-arm the centre focus while
            // we are still looking; the loop stops the moment a code resolves and the state leaves
            // Scanning, so a working phone triggers this once and moves on.
            LaunchedEffect(camera, state) {
                val control = camera?.cameraControl ?: return@LaunchedEffect
                if (state != ScanState.Scanning) return@LaunchedEffect

                val centre = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
                val focus = FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(REFOCUS_SECONDS, TimeUnit.SECONDS)
                    .build()
                while (isActive) {
                    // Devices that cannot meter reject the action rather than ignoring it.
                    runCatching { control.startFocusAndMetering(focus) }
                    delay(TimeUnit.SECONDS.toMillis(REFOCUS_SECONDS))
                }
            }

            CameraPreview(
                onCodeScanned = viewModel::onCodeScanned,
                onCameraReady = { camera = it },
                scanning = state == ScanState.Scanning,
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
                        notice = current.notice,
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
                        // Not "today": a stationed night shift opens on one date and closes on the
                        // next, and the post it must close at belongs to the shift, not the day.
                        title = "Not your post for this shift",
                        body = "You timed in at ${current.timedInAt}. A stationed shift ends where " +
                            "it began, so scan ${current.timedInAt} to time out — not " +
                            "${current.scanned.code}.",
                        colour = SyncFailed,
                        onDismiss = viewModel::scanAgain,
                    )

                    // The shift is over. Said as the good news it is — the guard finished — not as a
                    // refusal, and it names when they can clock on again.
                    is ScanState.ShiftComplete -> Result(
                        title = "Shift complete",
                        body = "You have already timed out. Your shift is done — time in again at " +
                            "the start of your next shift.",
                        colour = SyncSynced,
                        onDismiss = viewModel::scanAgain,
                    )

                    // A rest day is not an error, and is not worded as one.
                    ScanState.NotScheduledToday -> Result(
                        title = "No shift today",
                        body = "You are not scheduled for a shift today, so there is no attendance " +
                            "to record. If that looks wrong, check with the office.",
                        colour = SyncPending,
                        onDismiss = viewModel::scanAgain,
                    )

                    // Not the guard's fault, and not something they can fix by scanning again.
                    ScanState.NotOnRoster -> Result(
                        title = "Account not linked to a guard",
                        body = "Your login has not been connected to a guard, so the app cannot " +
                            "tell what you are scheduled for. Ask the office to link your account.",
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
            // A bolt, not the QR glyph this used to show. The scanner icon on the torch button
            // said "scan" on the one control that does not scan, next to a viewfinder already
            // full of scanning — so the way to get light at an unlit post was to guess.
            painter = painterResource(R.drawable.ic_flashlight),
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
    notice: String?,
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
        // Why a button they expected is not there. Shown above the buttons, not below: a guard who
        // has already tapped has stopped reading.
        if (notice != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SyncPending.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

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

        // When every rule has fired there is nothing to record here, and this is the only thing
        // left to do — so it stops being the quiet way out and becomes the action. A card whose
        // only control is styled as an afterthought reads as a dead end.
        if (allowedTypes.isEmpty()) {
            TypeButton(
                label = "Scan another checkpoint",
                filled = true,
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Scan a different checkpoint", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    scanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Analysis runs off the main thread; a single thread is enough and keeps ordering simple.
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    // The analyzer fires once and then latches, so a QR code is resolved a single time. Re-arm it
    // each time the screen returns to scanning — otherwise "Scan again" after a result leaves the
    // latch set and the scanner reads nothing, looking broken.
    val analyzer = remember { QrAnalyzer(onCodeScanned) }
    LaunchedEffect(scanning) { if (scanning) analyzer.reset() }

    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    runCatching {
                        val provider = providerFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            // Dropping stale frames keeps the preview responsive; a QR code stays
                            // in view long enough that no frame is worth queueing for.
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(ANALYSIS_RESOLUTION)
                            .build()
                            .also { it.setAnalyzer(executor, analyzer) }

                        provider.unbindAll()
                        onCameraReady(
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        )
                    }.onFailure { error ->
                        // A camera that never binds shows a black screen and nothing else. Say so
                        // in logcat at least, rather than leaving the guard and whoever they call
                        // staring at an unexplained void.
                        Log.e(TAG, "could not start the scanner camera", error)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Analysis frames default to roughly 640x480, and on a phone with a wide main sensor a checkpoint
 * sticker held at arm's length lands on too few pixels for ML Kit to resolve the modules — the code
 * is plainly in frame and simply never decodes, on that handset only. 720p carries enough detail at
 * that distance while staying inside the preview-plus-analysis combination every device guarantees;
 * 1080p analysis is not guaranteed on LEGACY camera hardware and can fail to bind outright.
 */
private val ANALYSIS_RESOLUTION = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
    )
    .build()

/** Long enough that a focused camera is left alone, short enough that a blurred one recovers. */
private const val REFOCUS_SECONDS = 3L

private const val TAG = "ScanQrScreen"

private fun AttendanceType.label(): String = when (this) {
    AttendanceType.TIME_IN -> "Time In"
    AttendanceType.TIME_OUT -> "Time Out"
    AttendanceType.CHECKPOINT -> "Checkpoint"
}
