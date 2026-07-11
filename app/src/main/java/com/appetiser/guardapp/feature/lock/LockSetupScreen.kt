package com.appetiser.guardapp.feature.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appetiser.guardapp.R
import com.appetiser.guardapp.core.security.LockPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

@HiltViewModel
class LockSetupViewModel @Inject constructor(
    private val lockPreferences: LockPreferences,
) : ViewModel() {

    /** Enabling and skipping both mark the prompt seen, so it never shows twice. */
    fun enable() = viewModelScope.launch {
        lockPreferences.setEnabled(true)
        lockPreferences.markSetupSeen()
    }

    fun skip() = viewModelScope.launch {
        lockPreferences.setEnabled(false)
        lockPreferences.markSetupSeen()
    }
}

/**
 * The one-time, skippable offer to secure the app. Shown once after the first login. Choosing
 * Enable first confirms a biometric or device credential actually works, so the guard is not
 * left with a lock they cannot open; Skip leaves the app unlocked and can be reversed from the
 * Account screen.
 */
@Composable
fun LockSetupScreen(viewModel: LockSetupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    var error by remember { mutableStateOf<String?>(null) }

    fun confirmThenEnable() {
        val host = activity ?: run { error = "Cannot start the check on this screen."; return }
        if (BiometricManager.from(host).canAuthenticate(ALLOWED) != BiometricManager.BIOMETRIC_SUCCESS) {
            error = "Set a screen lock (PIN, pattern, or fingerprint) on your phone first."
            return
        }
        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.enable()
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    error = message.toString()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Turn on app lock")
                .setSubtitle("Confirm the lock you'll use to open GuardApp")
                .setAllowedAuthenticators(ALLOWED)
                .build()
        )
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                painter = painterResource(R.drawable.ic_finger_print),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(24.dp).size(40.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Secure GuardApp",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Require your fingerprint, face, or device PIN each time the app reopens. You can " +
                "change this any time under Account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { error = null; confirmThenEnable() },
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text("Enable app lock", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = viewModel::skip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
