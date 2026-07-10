package com.appetiser.guardapp.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.appetiser.guardapp.R
import com.appetiser.guardapp.domain.model.GuardProfile
import com.appetiser.guardapp.domain.repository.ProfileRepository
import com.appetiser.guardapp.ui.components.GuardCard
import com.appetiser.guardapp.ui.components.ScreenTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    profiles: ProfileRepository,
) : ViewModel() {
    val profile: StateFlow<GuardProfile?> = profiles.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * Reports and Settings live here. The bottom bar mirrors the portal exactly (Home, Scan QR,
 * Generate QR, History, Account), which leaves no bar slot for the PRD's Reports and Settings
 * destinations.
 */
@Composable
fun AccountScreen(viewModel: AccountViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Account")

        ProfileCard(profile)

        GuardCard {
            AccountRow(R.drawable.ic_grades, "Reports", "Daily, weekly and monthly summaries")
            Divider()
            AccountRow(R.drawable.ic_document, "Duties & responsibilities", "Read the current revision")
            Divider()
            AccountRow(R.drawable.ic_locator, "Checkpoints", "Cached for offline scanning")
        }

        GuardCard {
            AccountRow(R.drawable.ic_dtr, "Sync now", "Upload any queued attendance")
            Divider()
            AccountRow(R.drawable.ic_bell, "Notifications", "Announcements from the office")
            Divider()
            AccountRow(R.drawable.ic_send, "Sign out", "Clear this session on the device")
        }

        Text(
            "GuardApp 0.1.0",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileCard(profile: GuardProfile?) {
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val initials = profile?.name.orEmpty()
                .split(' ').filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercase() }
                .ifEmpty { "?" }

            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Text(
                        initials,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    profile?.name ?: "Not signed in",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    profile?.username?.uppercase() ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AccountRow(iconRes: Int, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.padding(10.dp).size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(0.dp))
}
