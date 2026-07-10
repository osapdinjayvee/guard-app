package com.appetiser.guardapp.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Requests [permissions] once, then renders [content] when they are all granted.
 *
 * Android permanently denies a permission after two refusals, and
 * `shouldShowRequestPermissionRationale` returns false in that state — the system dialog will
 * never appear again. That case gets a button to app settings instead of a retry that would
 * silently do nothing, and the grant is re-checked on resume so returning from settings works.
 */
@Composable
fun PermissionGate(
    permissions: List<String>,
    iconRes: Int,
    title: String,
    rationale: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasAll(permissions)) }
    var askedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        askedOnce = true
    }

    // Returning from app settings does not recompose on its own.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableLifecycleObserver(lifecycleOwner.lifecycle) { event ->
        if (event == Lifecycle.Event.ON_RESUME) granted = context.hasAll(permissions)
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(permissions.toTypedArray())
    }

    if (granted) {
        content()
        return
    }

    val permanentlyDenied = askedOnce && !context.canAskAgain(permissions)

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(22.dp).size(34.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (permanentlyDenied) {
                "$rationale\n\nYou have denied this permission, so Android will not ask again. " +
                    "Enable it in app settings."
            } else {
                rationale
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                if (permanentlyDenied) context.openAppSettings()
                else launcher.launch(permissions.toTypedArray())
            },
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                if (permanentlyDenied) "Open settings" else "Allow",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DisposableLifecycleObserver(
    lifecycle: Lifecycle,
    onEvent: (Lifecycle.Event) -> Unit,
) {
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> onEvent(event) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

private fun Context.hasAll(permissions: List<String>): Boolean = permissions.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

/** False once Android has permanently denied: the system dialog will never show again. */
private fun Context.canAskAgain(permissions: List<String>): Boolean {
    val activity = this as? Activity ?: findActivity() ?: return true
    return permissions.any { activity.shouldShowRequestPermissionRationale(it) }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
