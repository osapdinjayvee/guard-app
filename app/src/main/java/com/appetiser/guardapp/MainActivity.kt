package com.appetiser.guardapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.appetiser.guardapp.ui.navigation.GuardAppScaffold
import com.appetiser.guardapp.ui.theme.GuardAppTheme
import dagger.hilt.android.AndroidEntryPoint

/** Single-activity host. All navigation lives in Compose. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GuardAppTheme {
                GuardAppScaffold()
            }
        }
    }
}
