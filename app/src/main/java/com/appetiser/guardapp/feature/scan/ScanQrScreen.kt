package com.appetiser.guardapp.feature.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appetiser.guardapp.R
import com.appetiser.guardapp.ui.components.EmptyState
import com.appetiser.guardapp.ui.components.ScreenTitle

/** Camera scanning arrives in T-17 (CameraX + ML Kit). */
@Composable
fun ScanQrScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        ScreenTitle("Scan QR")
        EmptyState(
            iconRes = R.drawable.ic_nav_scan,
            title = "Camera not wired up yet",
            body = "Point at a checkpoint QR to record attendance",
            modifier = Modifier.padding(bottom = 80.dp),
        )
    }
}
