package com.minsu.guardapp.feature.reference

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.repository.ScheduleRepository
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.ScreenTitle
import com.minsu.guardapp.ui.format.shiftTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class ScheduleUiState(
    val days: List<DutyAssignment> = emptyList(),
    val today: String = "",
    /** False when nobody has joined this login to a guard on the roster. Not the same as "no shifts". */
    val linked: Boolean = true,
)

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    schedule: ScheduleRepository,
) : ViewModel() {

    val uiState: StateFlow<ScheduleUiState> = combine(
        schedule.observeAll(),
        schedule.isLinked,
    ) { days, linked ->
        ScheduleUiState(days = days, today = todayIso(), linked = linked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState())
}

/**
 * The guard's own roster, read from the phone.
 *
 * A guard needs to know whether they are stationed or roving tomorrow, and when they start, and they
 * need to know it in the places they actually are — a guardhouse, a perimeter post, home. So this is
 * read from the cache and never waits on a network.
 *
 * It is also the answer to a question the app was otherwise silently deciding for them: the roster
 * already governed what the scanner would let them do, and until now they could not see it.
 */
@Composable
fun ScheduleScreen(viewModel: ScheduleViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("My schedule")

        when {
            // An admin error, and named as one. "You have no shifts" would be a different sentence
            // about a different problem, and would leave the guard waiting for a rota that is never
            // going to arrive.
            !state.linked -> Notice(
                "Your account is not on the duty roster",
                "Nobody has connected this login to a guard on the roster, so the app cannot see " +
                    "what you are scheduled for. Ask the office to link your account.",
            )

            state.days.isEmpty() -> Notice(
                "No shifts downloaded yet",
                "Open this screen while you have a connection and your roster will be saved to " +
                    "this phone.",
            )

            else -> {
                Text(
                    "Saved on this phone, so it works with no signal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.days.forEach { day ->
                    DayRow(day = day, isToday = day.date == state.today)
                }
            }
        }
    }
}

/**
 * One rostered day.
 *
 * The two things a guard came here for are *when* — which day, and what hours — so those are what
 * the row leads with: a calendar-style date block, and the shift hours as the headline. The duty
 * code and the rule it implies are the supporting detail, not the other way round.
 */
@Composable
private fun DayRow(day: DutyAssignment, isToday: Boolean) {
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateBlock(day.date, isToday)

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                val from = shiftTime(day.startsAt)
                val to = shiftTime(day.endsAt)
                Text(
                    if (from != null && to != null) "$from – $to" else "Hours not set",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (from != null && to != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // SG and RG are what the roster says and what the guards call each other.
                    DutyBadge(day.dutyType)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        day.dutyName ?: day.dutyType.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isToday) TodayPill()
        }

        // What the duty actually means for them, in the terms the scanner will enforce. A guard who
        // reads "Stationed" and then finds the app refusing their second checkpoint has been told
        // nothing useful.
        Spacer(Modifier.height(10.dp))
        Text(
            when (day.dutyType) {
                DutyType.STATIONED ->
                    "Time In and Time Out at one checkpoint — wherever you scan in, you scan out."
                DutyType.ROVING ->
                    "Time In to start, a checkpoint scan at each post on your round, Time Out to end."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The date, sized so it can be found by glancing down the column rather than by reading. */
@Composable
private fun DateBlock(iso: String, isToday: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isToday) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Column(
            // Tall enough for all three lines. At 68.dp the month was silently clipped off the
            // bottom, which is the one line that matters when the roster crosses into August.
            modifier = Modifier
                .size(width = 64.dp, height = 84.dp)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val onBlock = if (isToday) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                datePart(iso, "EEE").uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = onBlock,
            )
            Text(
                datePart(iso, "d"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = onBlock,
            )
            Text(
                datePart(iso, "MMM").uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = onBlock,
            )
        }
    }
}

@Composable
private fun DutyBadge(type: DutyType) {
    val stationed = type == DutyType.STATIONED
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (stationed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
    ) {
        Text(
            if (stationed) "SG" else "RG",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (stationed) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun TodayPill() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            "TODAY",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Notice(title: String, body: String) {
    GuardCard {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}

private fun todayIso(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** One piece of `2026-07-13` — `EEE` → `Mon`, `d` → `13`, `MMM` → `Jul`. */
private fun datePart(iso: String, pattern: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
    SimpleDateFormat(pattern, Locale.getDefault()).format(parsed)
}.getOrDefault(if (pattern == "d") iso.takeLast(2) else "")
