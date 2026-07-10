package com.appetiser.guardapp.feature.home

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appetiser.guardapp.R
import com.appetiser.guardapp.domain.model.Announcement
import com.appetiser.guardapp.domain.model.AttendanceRecord
import com.appetiser.guardapp.domain.model.GuardProfile
import com.appetiser.guardapp.domain.model.SyncState
import com.appetiser.guardapp.ui.theme.GuardAppTheme
import com.appetiser.guardapp.ui.theme.SyncFailed
import com.appetiser.guardapp.ui.theme.SyncPending
import com.appetiser.guardapp.ui.theme.SyncSynced
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state)
}

@Composable
fun HomeScreen(state: HomeUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Greeting(state.profile, state.isOnline)

        state.maintenanceMessage?.let { MaintenanceBanner(it) }

        SyncCard(pendingCount = state.pendingSyncCount)

        LastAttendanceCard(state.lastRecord)

        if (state.announcements.isNotEmpty()) {
            Text(
                "Announcements",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            state.announcements.forEach { AnnouncementCard(it) }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Greeting(profile: GuardProfile?, isOnline: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "Welcome back",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                profile?.name ?: "…",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        ConnectivityPill(isOnline)
    }
}

@Composable
private fun ConnectivityPill(isOnline: Boolean) {
    val colour = if (isOnline) SyncSynced else SyncPending
    Surface(shape = CircleShape, color = colour.copy(alpha = 0.12f)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(colour, CircleShape))
            Text(
                if (isOnline) "Online" else "Offline",
                style = MaterialTheme.typography.labelMedium,
                color = colour,
            )
        }
    }
}

@Composable
private fun MaintenanceBanner(message: String) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = SyncPending.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SyncCard(pendingCount: Int) {
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_history)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (pendingCount == 0) "All records synced" else "Waiting to sync",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (pendingCount == 0) {
                        "Nothing queued on this device"
                    } else {
                        "$pendingCount record${if (pendingCount == 1) "" else "s"} not yet uploaded"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pendingCount > 0) {
                Surface(shape = CircleShape, color = SyncPending) {
                    Text(
                        pendingCount.toString(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun LastAttendanceCard(record: AttendanceRecord?) {
    GuardCard {
        Text(
            "Last attendance",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (record == null) {
            Text(
                "No attendance recorded on this device yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                "${record.type.name.replace('_', ' ')} · ${record.checkpointCode}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    timestamp(record.capturedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SyncBadge(record.syncState)
            }
        }
    }
}

@Composable
private fun SyncBadge(state: SyncState) {
    val (label, colour) = when (state) {
        SyncState.SYNCED -> "Synced" to SyncSynced
        SyncState.PENDING, SyncState.SYNCING -> "Pending" to SyncPending
        SyncState.FAILED -> "Retrying" to SyncPending
        SyncState.REJECTED -> "Rejected" to SyncFailed
    }
    Surface(shape = CircleShape, color = colour.copy(alpha = 0.12f)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colour,
        )
    }
}

@Composable
private fun AnnouncementCard(announcement: Announcement) {
    GuardCard {
        Text(
            announcement.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            announcement.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IconBadge(iconRes: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(10.dp).size(22.dp),
        )
    }
}

@Composable
private fun GuardCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

private fun timestamp(millis: Long): String =
    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(millis))

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    GuardAppTheme {
        HomeScreen(
            HomeUiState(
                profile = GuardProfile(7, "Juan Dela Cruz", "guard01"),
                pendingSyncCount = 2,
                announcements = listOf(Announcement(1, "Payroll cut-off moved", "Now the 25th.")),
                isOnline = false,
            )
        )
    }
}
