package com.appetiser.guardapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.appetiser.guardapp.core.common.Clock
import com.appetiser.guardapp.ui.theme.GuardAppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PlaceholderViewModel @Inject constructor(
    private val clock: Clock,
) : ViewModel() {
    val startedAtMillis: Long = clock.nowMillis()
}

/**
 * Temporary landing screen. Replaced by the bottom-navigation scaffold in T-6.
 */
@Composable
fun PlaceholderScreen(
    modifier: Modifier = Modifier,
    viewModel: PlaceholderViewModel = hiltViewModel(),
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "GuardApp", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Attendance capture — not yet implemented",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "session started at ${viewModel.startedAtMillis}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// The preview cannot use hiltViewModel(), so it renders the stateless content only.
@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    GuardAppTheme {
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "GuardApp", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}
