package com.minsu.guardapp.feature.reference

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minsu.guardapp.R
import com.minsu.guardapp.domain.model.Announcement
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.domain.repository.AnnouncementRepository
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.DutyRepository
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.ScreenTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * The three reference screens behind Home's tiles.
 *
 * Every one of them reads the local cache and never blocks on the network. They are what a guard
 * opens to check something — which posts exist, what they agreed to, what the office said — and
 * those questions are asked in exactly the places with no signal.
 */

// ---------------------------------------------------------------------------------------------
// Checkpoints

@HiltViewModel
class CheckpointsViewModel @Inject constructor(
    checkpoints: CheckpointRepository,
) : ViewModel() {
    val checkpoints: StateFlow<List<Checkpoint>> = checkpoints.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun CheckpointsScreen(viewModel: CheckpointsViewModel = hiltViewModel()) {
    val checkpoints by viewModel.checkpoints.collectAsStateWithLifecycle()

    ReferencePage("Checkpoints") {
        if (checkpoints.isEmpty()) {
            Empty(
                "No checkpoints downloaded yet",
                "Open Home while you have a connection and they will be saved to this phone.",
            )
            return@ReferencePage
        }

        Text(
            "${checkpoints.size} post(s), saved on this phone so scanning works with no signal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        checkpoints.forEach { checkpoint ->
            GuardCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(
                            painter = painterResource(R.drawable.ic_locator),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.padding(10.dp).size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            checkpoint.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            checkpoint.code,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Duties

@HiltViewModel
class DutiesViewModel @Inject constructor(
    private val duties: DutyRepository,
) : ViewModel() {
    private val _duty = MutableStateFlow<Duty?>(null)
    val duty: StateFlow<Duty?> = _duty

    init {
        viewModelScope.launch { _duty.value = duties.activeDuty() }
    }
}

@Composable
fun DutiesScreen(viewModel: DutiesViewModel = hiltViewModel()) {
    val duty by viewModel.duty.collectAsStateWithLifecycle()

    ReferencePage("Duties") {
        val current = duty
        if (current == null) {
            Empty(
                "No duties downloaded yet",
                "Open Home while you have a connection to save the current duties to this phone.",
            )
            return@ReferencePage
        }

        GuardCard {
            Text(
                current.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                current.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // The version, spelled out. Every attendance stores the id of the revision that was
        // acknowledged, so a guard can always see which text they actually agreed to.
        Text(
            "Revision ${current.id}. This is the version recorded against your attendance.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Announcements

@HiltViewModel
class AnnouncementsViewModel @Inject constructor(
    announcements: AnnouncementRepository,
) : ViewModel() {
    val announcements: StateFlow<List<Announcement>> = announcements.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun AnnouncementsScreen(viewModel: AnnouncementsViewModel = hiltViewModel()) {
    val announcements by viewModel.announcements.collectAsStateWithLifecycle()

    ReferencePage("Announcements") {
        if (announcements.isEmpty()) {
            Empty("Nothing from the office", "Announcements posted by the office will appear here.")
            return@ReferencePage
        }

        announcements.forEach { announcement ->
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun ReferencePage(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(title)
        content()
    }
}

/** Says why it is empty and what to do about it — never just a blank screen. */
@Composable
private fun Empty(title: String, body: String) {
    GuardCard {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
