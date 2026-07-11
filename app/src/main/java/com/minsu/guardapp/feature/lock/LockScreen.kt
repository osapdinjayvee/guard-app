package com.minsu.guardapp.feature.lock

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.minsu.guardapp.R

private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/**
 * The unlock screen shown when a signed-in app returns from the background. It prompts for a
 * biometric or the device credential (PIN, pattern, password) — no app-specific PIN to set up
 * or store, and it inherits whatever lock the guard already trusts on their phone.
 *
 * The prompt is launched automatically on entry and re-launchable if the guard dismisses it.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    var error by remember { mutableStateOf<String?>(null) }

    fun authenticate() {
        val host = activity ?: run { error = "Cannot show the lock screen."; return }
        val canAuth = BiometricManager.from(host).canAuthenticate(ALLOWED)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometric enrolled and no device lock set: nothing to check against, so do not
            // strand the guard out of their own attendance. Let them through.
            onUnlocked()
            return
        }

        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    error = message.toString()
                }
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock GuardApp")
                .setSubtitle("Confirm it's you to continue")
                .setAllowedAuthenticators(ALLOWED)
                .build()
        )
    }

    LaunchedEffect(Unit) { authenticate() }

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
            "GuardApp is locked",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            error ?: "Unlock with your fingerprint, face, or device PIN.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { error = null; authenticate() },
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("Unlock", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
