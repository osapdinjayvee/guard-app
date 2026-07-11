package com.appetiser.guardapp.feature.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appetiser.guardapp.R
import com.appetiser.guardapp.core.onboarding.OnboardingPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class OnboardingStep(
    @DrawableRes val icon: Int,
    val title: String,
    val body: String,
)

private val steps = listOf(
    OnboardingStep(
        R.drawable.ic_nav_scan,
        "Scan the checkpoint",
        "Point your camera at a checkpoint's QR code to start recording your attendance.",
    ),
    OnboardingStep(
        R.drawable.ic_finger_print,
        "Prove you were there",
        "Take a selfie stamped with the date, time, location, and checkpoint — captured even with no signal.",
    ),
    OnboardingStep(
        R.drawable.ic_nav_history,
        "Syncs when you're online",
        "Every record is saved on your phone first and uploaded automatically once you have a connection.",
    ),
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboarding: OnboardingPreferences,
) : ViewModel() {
    fun finish() = viewModelScope.launch { onboarding.markCompleted() }
}

/** The one-time device intro. Finishing it (or skipping) hands off to login. */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val pager = rememberPagerState(pageCount = { steps.size })
    val scope = rememberCoroutineScope()
    val onLast = pager.currentPage == steps.lastIndex

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
    ) {
        TextButton(
            onClick = viewModel::finish,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            Step(steps[page])
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            steps.indices.forEach { i ->
                val active = i == pager.currentPage
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (active) 10.dp else 8.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        )
                )
            }
        }

        Button(
            onClick = {
                if (onLast) viewModel.finish()
                else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            },
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text(if (onLast) "Get started" else "Next", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Step(step: OnboardingStep) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                painter = painterResource(step.icon),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(28.dp).size(48.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            step.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            step.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
