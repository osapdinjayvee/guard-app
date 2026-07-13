package com.minsu.guardapp.feature.home

import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minsu.guardapp.R
import com.minsu.guardapp.domain.model.Announcement
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.domain.model.SyncState
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.QuickAction
import com.minsu.guardapp.ui.components.SearchField
import com.minsu.guardapp.ui.components.SectionHeading
import com.minsu.guardapp.ui.format.shiftTime
import com.minsu.guardapp.ui.theme.Accent
import com.minsu.guardapp.ui.theme.GuardAppTheme
import com.minsu.guardapp.ui.theme.SyncFailed
import com.minsu.guardapp.ui.theme.SyncPending
import com.minsu.guardapp.ui.theme.SyncSynced
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where Home's tiles lead. The scaffold owns the navigator; Home only says where it wants to go. */
enum class HomeAction { Scan, History, Reports, Schedule, Checkpoints, Duties, Announcements, Account }

@Composable
fun HomeRoute(
    onAction: (HomeAction) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state, onAction = onAction)
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header(state.profile)
        IdentityRow(state.profile, state.isOnline)

        // The search field that used to sit here searched nothing. Removed rather than left as a
        // control that swallows taps: searching attendance is not a feature this app has.

        QuickActions(onAction)

        TodayDutyCard(state.todayDuty, onOpen = { onAction(HomeAction.Schedule) })

        state.maintenanceMessage?.let { MaintenanceBanner(it) }

        // Not "Today" — the duty card above already owns that word, and these two cards are about
        // what this handset is holding, not about the shift.
        SectionHeading("On this device")
        SyncCard(state.pendingSyncCount)
        LastAttendanceCard(state.lastRecord)

        if (state.announcements.isNotEmpty()) {
            SectionHeading("Announcements")
            state.announcements.forEach { AnnouncementCard(it) }
        }
    }
}

@Composable
private fun Header(profile: GuardProfile?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_minsuverse),
            contentDescription = "MinSUverse",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(34.dp).width(150.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Mabuhay at Mahaltana,",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    profile?.name?.substringBefore(' ') ?: "…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Avatar(profile?.name)
        }
    }
}

@Composable
private fun Avatar(name: String?) {
    val initials = name.orEmpty()
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Text(
                initials,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun IdentityRow(profile: GuardProfile?, isOnline: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_badge),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                profile?.username?.uppercase() ?: "—",
                style = MaterialTheme.typography.titleSmall,
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
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * What the guard is on today, on the screen they open first.
 *
 * The roster already governs what the scanner will accept — a stationed guard is refused a second
 * checkpoint, and an unrostered day is refused outright. Deciding that silently and only telling the
 * guard at the moment of refusal, with a camera open and a shift starting, is the wrong order.
 */
@Composable
private fun TodayDutyCard(duty: DutyAssignment?, onOpen: () -> Unit) {
    GuardCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (duty == null) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when (duty?.dutyType) {
                            DutyType.STATIONED -> "SG"
                            DutyType.ROVING -> "RG"
                            null -> "—"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (duty == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // The day, then the hours, then everything else. A guard glancing at Home before a
                // shift is asking "when am I on" — so that is the line set in the largest type.
                Text(
                    "TODAY · ${today()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                val from = duty?.startsAt?.let(::shiftTime)
                val to = duty?.endsAt?.let(::shiftTime)
                Text(
                    when {
                        duty == null -> "Not on duty"
                        from != null && to != null -> "$from – $to"
                        else -> "Hours not set"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (duty == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    when (duty) {
                        null -> "You are not rostered for a shift today"
                        else -> "${duty.dutyName ?: duty.dutyType.name} · tap to see the week"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `Mon, 13 Jul` — today, as a guard would say it. */
private fun today(): String =
    SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())

private data class Action(val icon: Int, val label: String, val destination: HomeAction)

@Composable
private fun QuickActions(onAction: (HomeAction) -> Unit) {
    // Every tile goes somewhere. A tile that does nothing when tapped is worse than an absent one:
    // it teaches the guard that the app is broken, and they stop trusting the tiles that do work.
    val actions = listOf(
        Action(R.drawable.ic_finger_print, "Attendance", HomeAction.Scan),
        Action(R.drawable.ic_dtr, "DTR", HomeAction.History),
        Action(R.drawable.ic_grades, "Reports", HomeAction.Reports),
        Action(R.drawable.ic_calendar, "Schedule", HomeAction.Schedule),
        Action(R.drawable.ic_locator, "Checkpoints", HomeAction.Checkpoints),
        Action(R.drawable.ic_document, "Duties", HomeAction.Duties),
        Action(R.drawable.ic_megaphone, "Announcement", HomeAction.Announcements),
        Action(R.drawable.ic_profile, "Profile", HomeAction.Account),
    )

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        actions.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                row.forEach { action ->
                    QuickAction(
                        iconRes = action.icon,
                        label = action.label,
                        onClick = { onAction(action.destination) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps a short final row aligned to the same four-column grid instead of
                // stretching its tiles across the full width.
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MaintenanceBanner(message: String) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun SyncCard(pendingCount: Int) {
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    painter = painterResource(R.drawable.ic_dtr),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (pendingCount == 0) "All records synced" else "Waiting to sync",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
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
                Surface(shape = CircleShape, color = Accent) {
                    Text(
                        pendingCount.toString(),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondary,
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
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
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
fun SyncBadge(state: SyncState) {
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
            fontWeight = FontWeight.SemiBold,
            color = colour,
        )
    }
}

@Composable
private fun AnnouncementCard(announcement: Announcement) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Announcement",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                announcement.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                announcement.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            )
        )
    }
}
