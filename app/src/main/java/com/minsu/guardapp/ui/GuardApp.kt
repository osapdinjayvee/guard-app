package com.minsu.guardapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.minsu.guardapp.core.onboarding.OnboardingPreferences
import com.minsu.guardapp.core.security.AppLock
import com.minsu.guardapp.core.security.LockPreferences
import com.minsu.guardapp.core.session.SessionEvents
import com.minsu.guardapp.core.update.UpdateStatus
import com.minsu.guardapp.domain.repository.AuthRepository
import com.minsu.guardapp.feature.auth.LoginRoute
import com.minsu.guardapp.feature.lock.LockScreen
import com.minsu.guardapp.feature.lock.LockSetupScreen
import com.minsu.guardapp.feature.onboarding.OnboardingScreen
import com.minsu.guardapp.feature.update.UpdateRequiredScreen
import com.minsu.guardapp.feature.update.UpdateSheet
import com.minsu.guardapp.feature.update.UpdateViewModel
import com.minsu.guardapp.ui.navigation.GuardAppScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface AuthState {
    /** Before the stores have been read; showing anything here would flash on every launch. */
    data object Unknown : AuthState
    /** First launch on this device: the one-time intro, before login. */
    data object Onboarding : AuthState
    data object SignedOut : AuthState
    /** Signed in, but returned from background with the lock on: unlock required. */
    data object Locked : AuthState
    /** Signed in for the first time: offer the lock, which the guard may enable or skip. */
    data object NeedsLockSetup : AuthState
    data object SignedIn : AuthState
}

@HiltViewModel
class AuthGateViewModel @Inject constructor(
    auth: AuthRepository,
    private val appLock: AppLock,
    lockPreferences: LockPreferences,
    onboardingPreferences: OnboardingPreferences,
    /**
     * Injected only so the graph builds it: [SessionEvents] is emitted by the network layer
     * when a 401 arrives. AuthInterceptor clears the token, so [AuthRepository.isAuthenticated]
     * flips on its own and the gate swaps back to login without anyone navigating.
     */
    @Suppress("unused") private val sessionEvents: SessionEvents,
) : ViewModel() {

    val state: StateFlow<AuthState> =
        combine(
            onboardingPreferences.completed,
            auth.isAuthenticated,
            appLock.locked,
            lockPreferences.setupSeen,
        ) { onboarded, signedIn, locked, setupSeen ->
            when {
                !onboarded -> AuthState.Onboarding
                !signedIn -> AuthState.SignedOut
                locked -> AuthState.Locked
                !setupSeen -> AuthState.NeedsLockSetup
                else -> AuthState.SignedIn
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Unknown)

    fun unlock() = appLock.unlock()
}

/**
 * The auth gate. A 401 anywhere clears the token and returns the guard to login. A signed-in
 * app returning from the background shows the lock (only if the guard enabled it). On first
 * sign-in the guard is offered the lock once, which they may enable or skip.
 *
 * The queued attendance records survive every transition untouched.
 *
 * The update check sits in front of all of it. A build the server has disowned cannot do anything
 * useful whether or not anyone is signed in, and the manifest is a public file, so the block does
 * not depend on having a valid token to fetch it.
 */
@Composable
fun GuardApp(
    viewModel: AuthGateViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val update by updateViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { updateViewModel.checkQuietly() }

    if (update.isRequired) {
        UpdateRequiredScreen(
            state = update,
            onUpdate = updateViewModel::startUpdate,
            onRetry = updateViewModel::retryDownload,
        )
        return
    }

    when (state) {
        // Render nothing for the single frame before DataStore answers, rather than flashing
        // the login screen at a guard who is already signed in.
        AuthState.Unknown -> Unit
        AuthState.Onboarding -> OnboardingScreen()
        AuthState.SignedOut -> LoginRoute()
        AuthState.Locked -> LockScreen(onUnlocked = viewModel::unlock)
        AuthState.NeedsLockSetup -> LockSetupScreen()
        AuthState.SignedIn -> {
            GuardAppScaffold()

            // Only over the signed-in app. Interrupting onboarding or a login with a sheet about
            // versions would be noise at the exact moment the guard is trying to start a shift.
            if (update.status is UpdateStatus.Available) {
                UpdateSheet(
                    state = update,
                    onUpdate = updateViewModel::startUpdate,
                    onRetry = updateViewModel::retryDownload,
                    onDismiss = updateViewModel::dismiss,
                    onSelect = updateViewModel::select,
                )
            }
        }
    }
}
