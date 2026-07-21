package com.minsu.guardapp.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.network.ApiError
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.security.AppLock
import com.minsu.guardapp.core.sync.SyncScheduler
import com.minsu.guardapp.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = username.isNotBlank() && password.isNotBlank() && !isSubmitting
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val appLock: AppLock,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onUsernameChange(value: String) =
        _uiState.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) =
        _uiState.update { it.copy(password = value, error = null) }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            val result = auth.login(state.username, state.password)
            // The password itself is the authentication; do not immediately demand biometrics.
            if (result is ApiResult.Success) {
                appLock.unlock()
                // Drain this guard's queue now. On a device shared between guards, their records are
                // stranded while someone else is signed in — the worker only uploads the current
                // guard's. Signing back in is the moment their pending captures can finally go up, so
                // kick a sync rather than waiting for the next periodic pass.
                syncScheduler.requestSync()
            }
            _uiState.update {
                it.copy(
                    isSubmitting = false,
                    error = (result as? ApiResult.Failure)?.error?.let(::message),
                )
            }
            // On success the auth gate observes isAuthenticated and swaps the screen out;
            // this ViewModel does not navigate.
        }
    }

    /**
     * Messages the guard, not the developer. `ApiError.message` is backend prose that may be
     * reworded or missing, so the wording lives here and switches on the typed error.
     */
    private fun message(error: ApiError): String = when (error) {
        ApiError.InvalidCredentials -> "Wrong username or password."
        ApiError.Unauthorized -> "That session has expired. Sign in again."
        is ApiError.Network -> "No connection. Check your signal and try again."
        is ApiError.Server -> "The server is having trouble. Try again shortly."
        is ApiError.Validation -> error.fieldErrors.values.firstOrNull()?.firstOrNull()
            ?: "Check the details and try again."
        else -> "Something went wrong. Try again."
    }
}
