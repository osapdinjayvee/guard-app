package com.appetiser.guardapp

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.appetiser.guardapp.ui.GuardApp
import com.appetiser.guardapp.ui.theme.GuardAppTheme
import dagger.hilt.android.AndroidEntryPoint

/** Single-activity host. All navigation lives in Compose. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GuardAppTheme {
                GuardApp()
            }
        }
    }
}
