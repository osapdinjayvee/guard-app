package com.minsu.guardapp.feature.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minsu.guardapp.BuildConfig
import com.minsu.guardapp.core.update.DownloadState
import com.minsu.guardapp.ui.components.GuardCard

/**
 * The blocking update.
 *
 * Shown when the installed build is below the manifest's floor, which means it can no longer talk
 * to the server correctly. There is no way past it — no back gesture, no dismiss — because a
 * build that cannot submit attendance is worse than one that will not open: the guard would scan,
 * capture, acknowledge, and only then find out the record could not be sent.
 */
@Composable
fun UpdateRequiredScreen(state: UpdateUiState, onUpdate: () -> Unit, onRetry: () -> Unit) {
    BackHandler(enabled = true) { /* Deliberately inescapable. */ }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Update required",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This version of GuardApp can no longer record attendance. Install the update to carry on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        GuardCard { UpdateDetails(state) }

        Spacer(Modifier.height(20.dp))
        UpdateActions(state, onUpdate = onUpdate, onRetry = onRetry, onLater = null)
    }
}

/**
 * The optional update.
 *
 * Dismissible, and dismissal sticks until a newer build appears — a guard mid-shift with a
 * checkpoint to reach should not be asked twice about the same version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "Update available",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))

            GuardCard { UpdateDetails(state) }

            Spacer(Modifier.height(16.dp))
            UpdateActions(state, onUpdate = onUpdate, onRetry = onRetry, onLater = onDismiss)
        }
    }
}

@Composable
private fun UpdateDetails(state: UpdateUiState) {
    val update = state.available

    Text(
        "Version ${update?.versionName ?: "—"}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        "You have ${BuildConfig.VERSION_NAME}" +
            (update?.sizeBytes?.let { " · ${it.asMegabytes()} download" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    update?.releaseNotes?.let { notes ->
        Spacer(Modifier.height(12.dp))
        Text(
            notes,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    when (val download = state.download) {
        is DownloadState.Downloading -> {
            Spacer(Modifier.height(16.dp))
            val fraction = download.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                // No Content-Length and no size in the manifest: indeterminate is honest, a
                // progress bar guessing at a percentage is not.
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Downloading… ${download.bytes.asMegabytes()}" +
                    (download.total?.let { " of ${it.asMegabytes()}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is DownloadState.Failed -> {
            Spacer(Modifier.height(12.dp))
            Text(
                download.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        is DownloadState.Ready -> {
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.needsInstallPermission) {
                    "Android needs your permission to install apps from GuardApp. Allow it on the " +
                        "screen that opened, then tap Install."
                } else {
                    "Downloaded. Confirm the install when Android asks."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DownloadState.Idle -> Unit
    }
}

@Composable
private fun UpdateActions(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
    onLater: (() -> Unit)?,
) {
    val download = state.download
    val label = when (download) {
        is DownloadState.Downloading -> "Downloading…"
        is DownloadState.Failed -> "Try again"
        is DownloadState.Ready -> "Install"
        DownloadState.Idle -> "Update now"
    }

    Button(
        onClick = if (download is DownloadState.Failed) onRetry else onUpdate,
        enabled = download !is DownloadState.Downloading,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }

    if (onLater != null) {
        TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
            Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Long.asMegabytes(): String = "%.1f MB".format(this / 1_048_576.0)
