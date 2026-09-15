package com.minsu.guardapp.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.R
import com.minsu.guardapp.core.network.ApiError
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.repository.AuthRepository
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.ScreenTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChangePasswordUiState(
    val current: String = "",
    val new: String = "",
    val confirmation: String = "",
    val currentError: String? = null,
    val newError: String? = null,
    val confirmationError: String? = null,
    val error: String? = null,
    val isSubmitting: Boolean = false,
    val changed: Boolean = false,
) {
    val canSubmit: Boolean
        get() = current.isNotEmpty() && new.isNotEmpty() && confirmation.isNotEmpty() && !isSubmitting
}

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChangePasswordUiState())
    val uiState: StateFlow<ChangePasswordUiState> = _uiState.asStateFlow()

    fun onCurrentChange(value: String) = _uiState.update { it.copy(current = value, currentError = null, error = null) }

    fun onNewChange(value: String) = _uiState.update { it.copy(new = value, newError = null, error = null) }

    fun onConfirmationChange(value: String) =
        _uiState.update { it.copy(confirmation = value, confirmationError = null, error = null) }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        // The server checks all of these too. Checked here first so the obvious mistakes are said
        // without spending a round trip — and without a guard at a post with one bar of signal
        // waiting on the network to tell them two fields do not match.
        val newError = when {
            state.new.length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters."
            state.new == state.current -> "Choose a password different from your current one."
            else -> null
        }
        val confirmationError = if (state.confirmation != state.new) "The passwords do not match." else null

        if (newError != null || confirmationError != null) {
            _uiState.update { it.copy(newError = newError, confirmationError = confirmationError) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, error = null) }

        viewModelScope.launch {
            when (val result = auth.changePassword(state.current, state.new, state.confirmation)) {
                is ApiResult.Success -> _uiState.update {
                    // The passwords are dropped from memory the moment they have done their job.
                    ChangePasswordUiState(changed = true)
                }

                is ApiResult.Failure -> _uiState.update { it.withError(result.error) }
            }
        }
    }

    private fun ChangePasswordUiState.withError(error: ApiError): ChangePasswordUiState = when (error) {
        is ApiError.Validation -> copy(
            isSubmitting = false,
            currentError = error.fieldErrors["current_password"]?.firstOrNull(),
            newError = error.fieldErrors["password"]?.firstOrNull(),
            error = error.message.takeIf {
                error.fieldErrors["current_password"] == null && error.fieldErrors["password"] == null
            },
        )

        is ApiError.Network -> copy(
            isSubmitting = false,
            error = "You're offline. Changing your password needs a connection.",
        )

        // The session is already being cleared by the network layer; the guard lands on sign-in.
        ApiError.Unauthorized -> copy(isSubmitting = false, error = "Your session has expired. Sign in again.")

        // A server that has not been given the endpoint yet. Said plainly, so nobody retries all day.
        ApiError.NotFound -> copy(
            isSubmitting = false,
            error = "The server does not support changing passwords yet. Ask the office.",
        )

        else -> copy(isSubmitting = false, error = "Could not change your password. Try again shortly.")
    }

    private companion object {
        /** The server's own minimum. */
        const val MIN_LENGTH = 8
    }
}

@Composable
fun ChangePasswordScreen(
    onBack: () -> Unit,
    viewModel: ChangePasswordViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Change password", onBack = onBack)

        if (state.changed) {
            GuardCard {
                Text(
                    "Password changed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Use your new password next time you sign in. You stay signed in on this phone; " +
                        "any other device signed in to your account has been signed out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SubmitButton("Done", enabled = true, busy = false, onClick = onBack)
            return@Column
        }

        Text(
            "Enter your current password, then the new one twice. You need a connection for this.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PasswordField(
            label = "Current password",
            value = state.current,
            onValueChange = viewModel::onCurrentChange,
            error = state.currentError,
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Next,
        )
        PasswordField(
            label = "New password",
            value = state.new,
            onValueChange = viewModel::onNewChange,
            error = state.newError,
            supporting = "At least 8 characters.",
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Next,
        )
        PasswordField(
            label = "Confirm new password",
            value = state.confirmation,
            onValueChange = viewModel::onConfirmationChange,
            error = state.confirmationError,
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Done,
        )

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        SubmitButton(
            "Change password",
            enabled = state.canSubmit,
            busy = state.isSubmitting,
            onClick = viewModel::submit,
        )
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    enabled: Boolean,
    imeAction: ImeAction,
    supporting: String? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = (error ?: supporting)?.let { text -> { Text(text) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled) {
                Icon(
                    painter = painterResource(if (visible) R.drawable.ic_eye_off else R.drawable.ic_eye),
                    contentDescription = if (visible) "Hide password" else "Show password",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SubmitButton(label: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}
