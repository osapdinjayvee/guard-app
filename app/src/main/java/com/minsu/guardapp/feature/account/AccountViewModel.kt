package com.minsu.guardapp.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.connectivity.NetworkMonitor
import com.minsu.guardapp.core.security.LockPreferences
import com.minsu.guardapp.core.sync.SyncScheduler
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.AuthRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountUiState(
    val profile: GuardProfile? = null,
    val lockEnabled: Boolean = false,
    val isOnline: Boolean = true,
    /** Records that still owe the server an upload. */
    val pendingCount: Int = 0,
    /** Records the server failed or refused to take. These do not clear themselves. */
    val stuckCount: Int = 0,
    val isSyncing: Boolean = false,
    val settings: AppSettings = AppSettings(),
) {
    val hasQueue: Boolean get() = pendingCount > 0 || stuckCount > 0
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val attendance: AttendanceRepository,
    settings: SettingsRepository,
    scheduler: SyncScheduler,
    networkMonitor: NetworkMonitor,
    private val auth: AuthRepository,
    private val lockPreferences: LockPreferences,
) : ViewModel() {

    private val syncMessage = MutableStateFlow<String?>(null)

    /** One-shot result of a "Sync now" tap, shown in a snackbar and then dismissed. */
    val message: StateFlow<String?> = syncMessage

    val uiState: StateFlow<AccountUiState> = combine(
        profiles.observe(),
        lockPreferences.enabled,
        networkMonitor.isOnline,
        combine(
            attendance.observeUnsyncedCount(),
            attendance.observeStuckCount(),
            scheduler.observeSyncing(),
        ) { pending, stuck, syncing -> Triple(pending, stuck, syncing) },
        settings.observe(),
    ) { profile, locked, online, queue, appSettings ->
        AccountUiState(
            profile = profile,
            lockEnabled = locked,
            isOnline = online,
            pendingCount = queue.first,
            stuckCount = queue.second,
            isSyncing = queue.third,
            settings = appSettings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState())

    fun setLockEnabled(enabled: Boolean) = viewModelScope.launch {
        lockPreferences.setEnabled(enabled)
        // Once the guard makes a deliberate choice here, the one-time prompt is moot.
        lockPreferences.markSetupSeen()
    }

    /**
     * Drain the queue on request.
     *
     * Offline, this deliberately still runs. Re-queueing the stuck records costs nothing and the
     * work is constrained on connectivity, so it fires the moment signal returns — telling a guard
     * "you are offline, try later" would be both true and useless. What they are told instead is
     * that the records are safe and queued, which is the thing they actually want to know.
     */
    fun syncNow() = viewModelScope.launch {
        val state = uiState.value
        if (!state.hasQueue) {
            syncMessage.value = "Nothing to sync — every record is already uploaded."
            return@launch
        }

        val requeued = attendance.syncNow()

        syncMessage.value = when {
            !state.isOnline ->
                "You're offline. ${state.pendingCount + state.stuckCount} record(s) will upload as soon as you reconnect."
            requeued > 0 -> "Retrying $requeued record(s) the server would not take."
            else -> "Uploading ${state.pendingCount} record(s)…"
        }
    }

    fun messageShown() = syncMessage.update { null }

    /** Clears the session, and the lock choice so the next guard on a shared device is asked. */
    fun signOut() = viewModelScope.launch {
        lockPreferences.clear()
        auth.logout()
    }
}
