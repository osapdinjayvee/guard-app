package com.minsu.guardapp.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.connectivity.NetworkMonitor
import com.minsu.guardapp.domain.model.Announcement
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.domain.model.roundPosts
import com.minsu.guardapp.feature.reference.Stop
import com.minsu.guardapp.feature.reference.buildStops
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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
    /**
     * Posts on today's round that have not yet met their minimum, with what they have so far.
     *
     * Only the outstanding ones. A post that has reached its minimum leaves the list, so what
     * remains is exactly the work remaining — a guard mid-shift wants the short list of where to
     * walk next, not the whole round with most of it ticked.
     *
     * Empty for a stationed guard, who has one post and no round to walk.
     */
    val remainingStops: List<Stop> = emptyList(),
    /** Today, `yyyy-MM-dd`. Carried so the round's screens can be opened for the right day. */
    val todayDate: String = "",
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
                        remainingStops = current.remainingStops,
                        todayDate = current.todayDate,
                    )
                }
            }
        }

        /*
         * What is left of today's round.
         *
         * Assembled from the cached posts and the records this device already holds, so it answers
         * at 3am at a perimeter with no signal — which is exactly when a guard cannot remember
         * which doors they have already walked to.
         */
        viewModelScope.launch {
            val from = startOfToday()
            combine(
                checkpoints.observeActive(),
                attendance.observeInRange(from, from + DAY_MILLIS),
                settings.observe(),
                schedule.observeToday(),
            ) { posts, records, config, duty ->
                // A stationed guard has one post and no round. Offering them a list of doors to
                // walk to would be somebody else's job rendered as their outstanding work.
                if (duty?.dutyType != DutyType.ROVING) {
                    return@combine emptyList<Stop>()
                }

                buildStops(posts.roundPosts(), records, config.minVisitsPerCheckpoint)
                    .filterNot { it.isDone }
                    .sortedWith(compareBy({ it.visits }, { it.checkpoint.code }))
            }.collect { stops ->
                _uiState.update { it.copy(remainingStops = stops, todayDate = todayDate()) }
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

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /**
         * Midnight to midnight in the guard's own timezone, not UTC's.
         *
         * A 23:50 visit belongs to the day the guard thinks it is; bounding the day in UTC would
         * push a late-evening scan in Manila into tomorrow and drop it out of tonight's round.
         */
        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun todayDate(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
    }
}
