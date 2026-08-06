package com.minsu.guardapp.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.connectivity.NetworkMonitor
import com.minsu.guardapp.domain.model.Announcement
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.domain.repository.AnnouncementRepository
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.DocumentRepository
import com.minsu.guardapp.domain.repository.DutyRepository
import com.minsu.guardapp.domain.repository.EvaluationRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.repository.ScheduleRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val profile: GuardProfile? = null,
    val pendingSyncCount: Int = 0,
    /** Unsynced records captured by another guard on this shared device. 0 for a single-guard phone. */
    val otherAccountPendingCount: Int = 0,
    val lastRecord: AttendanceRecord? = null,
    val announcements: List<Announcement> = emptyList(),
    val maintenanceMessage: String? = null,
    val isOnline: Boolean = true,
    val isRefreshing: Boolean = false,
    /** Today's duty. Null on a rest day — which Home says plainly rather than leaving blank. */
    val todayDuty: DutyAssignment? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val attendance: AttendanceRepository,
    private val settings: SettingsRepository,
    private val announcements: AnnouncementRepository,
    private val checkpoints: CheckpointRepository,
    private val duties: DutyRepository,
    private val schedule: ScheduleRepository,
    private val evaluations: EvaluationRepository,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Local state streams straight off Room and DataStore, never waiting on the network.
        // An offline guard still sees their name and how many records they still owe.
        viewModelScope.launch {
            combine(
                profiles.observe(),
                attendance.observeUnsyncedCount(),
                attendance.observeHistory(limit = 1),
                settings.observe(),
                announcements.observe(),
            ) { profile, pending, recent, appSettings, notices ->
                HomeUiState(
                    profile = profile,
                    pendingSyncCount = pending,
                    lastRecord = recent.firstOrNull(),
                    maintenanceMessage = appSettings.maintenanceMessage,
                    announcements = notices,
                )
            }.collect { fresh ->
                // isOnline, isRefreshing, todayDuty and otherAccountPendingCount are owned by the
                // other collectors below; carry them across so a fresh emission here does not blank them.
                _uiState.update { current ->
                    fresh.copy(
                        isOnline = current.isOnline,
                        isRefreshing = current.isRefreshing,
                        todayDuty = current.todayDuty,
                        otherAccountPendingCount = current.otherAccountPendingCount,
                    )
                }
            }
        }

        viewModelScope.launch {
            attendance.observeOtherAccountUnsyncedCount().collect { count ->
                _uiState.update { it.copy(otherAccountPendingCount = count) }
            }
        }

        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                _uiState.update { it.copy(isOnline = online) }
            }
        }

        // The roster governs what the scanner will let the guard do. Until now they could not see it.
        viewModelScope.launch {
            schedule.observeToday().collect { duty ->
                _uiState.update { it.copy(todayDuty = duty) }
            }
        }

        refresh()
    }

    /**
     * Best effort. A failed refresh is not surfaced as an error: every value on this screen has
     * a local or cached source, so the screen stays useful offline. A red banner here would be
     * noise on a network the app is designed to work without.
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            profiles.refresh()
            settings.refresh()
            announcements.refresh()
            checkpoints.refresh()
            // The roster gates the scanner, and the scanner is used where there is no signal. It is
            // cached here, on the screen that is reliably online.
            schedule.refresh()
            // Cached here, on the one screen that is reliably online, because the place it is
            // *needed* is the acknowledgement gate at the end of a capture — which may well be
            // happening in a basement with no signal. Fetching it there would leave the guard
            // unable to submit an attendance they have already taken.
            duties.refresh()
            // Needed at Time Out, which happens at the end of a shift, at a post, with no signal.
            // Cached here for the same reason as the duties and the roster.
            evaluations.refresh()
            // Pulls back records this device does not have. Matters most on a device that has none
            // — a reinstall, cleared data, a replacement handset — where History and Reports would
            // otherwise read as though the guard's attendance had been lost with the app.
            attendance.refreshHistory()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }
}
