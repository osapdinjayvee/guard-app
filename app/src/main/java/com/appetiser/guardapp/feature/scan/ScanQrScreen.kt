package com.appetiser.guardapp.feature.scan

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appetiser.guardapp.R
import com.appetiser.guardapp.core.camera.QrAnalyzer
import com.appetiser.guardapp.ui.components.GuardCard
import com.appetiser.guardapp.ui.components.PermissionGate
import com.appetiser.guardapp.ui.components.ScreenTitle
import com.appetiser.guardapp.ui.theme.SyncFailed
import com.appetiser.guardapp.ui.theme.SyncPending
import java.util.concurrent.Executors

@Composable
fun ScanQrScreen(viewModel: ScanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        ScreenTitle("Scan QR")

        PermissionGate(
            permissions = listOf(Manifest.permission.CAMERA),
            iconRes = R.drawable.ic_nav_scan,
            title = "Camera access needed",
            rationale = "The camera reads the checkpoint QR code. It is used only while you are scanning.",
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CameraPreview(
                    onCodeScanned = viewModel::onCodeScanned,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp)),
                )

                Spacer(Modifier.height(20.dp))

                when (val current = state) {
                    ScanState.Scanning -> Instruction()
                    is ScanState.Resolved -> Result(
                        title = current.checkpoint.name,
                        body = "${current.checkpoint.code} · ready to record attendance",
                        colour = MaterialTheme.colorScheme.primary,
                        onDismiss = viewModel::scanAgain,
                    )
                    is ScanState.Disabled -> Result(
                        title = "Checkpoint retired",
                        body = "${current.checkpoint.code} is no longer in use. Try another checkpoint.",
                        colour = SyncPending,
                        onDismiss = viewModel::scanAgain,
                    )
                    is ScanState.Unknown -> Result(
                        title = "Unrecognised code",
                        body = "\"${current.code}\" is not a checkpoint in this system.",
                        colour = SyncFailed,
                        onDismiss = viewModel::scanAgain,
                    )
                }
            }
        }
    }
}

@Composable
private fun Instruction() {
    Text(
        "Point the camera at a checkpoint QR code",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
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
        ) {
            Text("Scan again", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CameraPreview(onCodeScanned: (String) -> Unit, modifier: Modifier = Modifier) {
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
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
