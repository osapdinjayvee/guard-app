package com.minsu.guardapp.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minsu.guardapp.BuildConfig
import com.minsu.guardapp.R
import com.minsu.guardapp.core.update.UpdateStatus
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.feature.update.UpdateUiState
import com.minsu.guardapp.feature.update.UpdateViewModel
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.ScreenTitle

@Composable
fun AccountScreen(
    viewModel: AccountViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val update by updateViewModel.uiState.collectAsStateWithLifecycle()
    val updateMessage by updateViewModel.snackbar.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbars.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    LaunchedEffect(updateMessage) {
        updateMessage?.let {
            snackbars.showSnackbar(it)
            updateViewModel.snackbarShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenTitle("Account")

            ProfileCard(state.profile)

            SyncCard(state, onSyncNow = viewModel::syncNow)

            GuardCard {
                ToggleRow(
                    iconRes = R.drawable.ic_finger_print,
                    title = "App lock",
                    subtitle = "Require biometrics or your device PIN to reopen",
                    checked = state.lockEnabled,
                    onCheckedChange = viewModel::setLockEnabled,
                )
            }

            CaptureCard(state.settings)

            AboutCard(
                update = update,
                isChecking = update.isChecking,
                onCheckForUpdates = updateViewModel::checkNow,
            )

            GuardCard {
                AccountRow(
                    R.drawable.ic_send,
                    "Sign out",
                    "Clear this session on the device",
                    onClick = viewModel::signOut,
                )
            }
        }
    }
}

/**
 * The queue, and the button that drains it.
 *
 * Sync state is user-visible state, not an internal detail: a guard whose phone is holding an
 * unsent attendance needs to know that, and needs to be able to do something about it.
 */
@Composable
private fun SyncCard(state: AccountUiState, onSyncNow: () -> Unit) {
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.hasQueue) "${state.pendingCount + state.stuckCount} record(s) waiting" else "All records synced",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        !state.isOnline && state.hasQueue ->
                            "Offline — they upload automatically when you reconnect"
                        !state.isOnline -> "Offline — nothing is waiting"
                        state.stuckCount > 0 ->
                            "${state.stuckCount} the server would not accept. Sync now to try again."
                        state.pendingCount > 0 -> "Uploading in the background"
                        else -> "Nothing queued on this device"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.stuckCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state.isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Divider()

        AccountRow(
            iconRes = R.drawable.ic_dtr,
            title = "Sync now",
            subtitle = "Upload any queued attendance, and retry anything stuck",
            onClick = onSyncNow,
        )
    }
}

/**
 * Capture settings are shown but not editable: they come from `GET /settings` and the server owns
 * them, so that tightening image quality across every handset is a config change rather than a
 * release. Presenting them as controls the guard could change would be a lie.
 */
@Composable
private fun CaptureCard(settings: AppSettings) {
    GuardCard {
        Text(
            "Capture",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        InfoRow("Selfie quality", "${settings.imageQuality}%")
        InfoRow("Selfie size limit", "${settings.imageMaxDimensionPx} px")
        InfoRow("GPS accuracy required", "${settings.gpsAccuracyThresholdMetres.toInt()} m")
        InfoRow(
            "Without GPS",
            if (settings.gpsFailurePolicy.name == "BLOCK") "Submission blocked" else "Submission allowed",
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Set by the office and applied to every device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutCard(update: UpdateUiState, isChecking: Boolean, onCheckForUpdates: () -> Unit) {
    GuardCard {
        Text(
            "About",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        InfoRow("Version", BuildConfig.VERSION_NAME)
        InfoRow("Server", BuildConfig.API_BASE_URL.toHost())

        Divider()

        // The deliberate path. The automatic check is silent by design — it has to be, or a
        // flaky connection would nag a guard mid-shift — so this is where someone who has been
        // told an update exists can go and get it.
        AccountRow(
            iconRes = R.drawable.ic_send,
            title = "Check for updates",
            subtitle = when {
                isChecking -> "Checking…"
                update.available != null -> "Version ${update.available?.versionName} is available"
                update.status is UpdateStatus.UpToDate -> "You're on the latest version"
                else -> "See whether a newer version has been released"
            },
            onClick = onCheckForUpdates,
        )
    }
}

/** Just the host: the guard needs to know *which* server, not the path it speaks to. */
private fun String.toHost(): String =
    runCatching { java.net.URI(this).host ?: this }.getOrDefault(this)

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProfileCard(profile: GuardProfile?) {
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val initials = profile?.name.orEmpty()
                .split(' ').filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercase() }
                .ifEmpty { "?" }

            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Text(
                        initials,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    profile?.name ?: "Not signed in",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    profile?.username?.uppercase() ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(10.dp).size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun AccountRow(iconRes: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(10.dp).size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.outline,
    )
}
