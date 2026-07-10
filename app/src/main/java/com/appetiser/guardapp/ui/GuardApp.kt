package com.appetiser.guardapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.appetiser.guardapp.core.session.SessionEvents
import com.appetiser.guardapp.domain.repository.AuthRepository
import com.appetiser.guardapp.feature.auth.LoginRoute
import com.appetiser.guardapp.ui.navigation.GuardAppScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface AuthState {
    /** Before the token store has been read; showing login here would flash on every launch. */
    data object Unknown : AuthState
    data object SignedIn : AuthState
    data object SignedOut : AuthState
}

@HiltViewModel
class AuthGateViewModel @Inject constructor(
    auth: AuthRepository,
    /**
     * Injected only so the graph builds it: [SessionEvents] is emitted by the network layer
     * when a 401 arrives. AuthInterceptor clears the token, so [AuthRepository.isAuthenticated]
     * flips on its own and the gate swaps back to login without anyone navigating.
     */
    @Suppress("unused") private val sessionEvents: SessionEvents,
) : ViewModel() {

    val state: StateFlow<AuthState> = auth.isAuthenticated
        .map { signedIn -> if (signedIn) AuthState.SignedIn else AuthState.SignedOut }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Unknown)
}

/**
 * The auth gate. A 401 anywhere in the app clears the token, which flips this flow and returns
 * the guard to login — no screen has to handle expiry itself.
 *
 * The queued attendance records survive that transition untouched.
 */
@Composable
fun GuardApp(viewModel: AuthGateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state) {
        // Render nothing for the single frame before DataStore answers, rather than flashing
        // the login screen at a guard who is already signed in.
        AuthState.Unknown -> Unit
        AuthState.SignedOut -> LoginRoute()
        AuthState.SignedIn -> GuardAppScaffold()
    }
}
