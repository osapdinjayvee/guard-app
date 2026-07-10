package com.appetiser.guardapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.appetiser.guardapp.ui.theme.GuardAppTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. Navigation lives in Compose; see [ui.GuardAppNavHost] once T-6 lands.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GuardAppTheme {
                PlaceholderScreen()
            }
        }
    }
}
