package com.minsu.guardapp.feature.reference

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.minsu.guardapp.core.common.Clock
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
    /** Today first, then the days ahead. What the guard opened the screen to find. */
    val upcoming: List<DutyAssignment> = emptyList(),
    /** Days already worked, kept below. Newest first, so the most recent shift is nearest the fold. */
    val past: List<DutyAssignment> = emptyList(),
    val today: String = "",
    /** False when nobody has joined this login to a guard on the roster. Not the same as "no shifts". */
    val linked: Boolean = true,
) {
    val isEmpty: Boolean get() = upcoming.isEmpty() && past.isEmpty()
}

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    schedule: ScheduleRepository,
    // Injected rather than read from the system, so "which of these days is today" is a thing a
    // test can pin down instead of a thing that depends on when the suite happens to run.
    private val clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<ScheduleUiState> = combine(
        schedule.observeAll(),
        schedule.isLinked,
    ) { days, linked ->
        // The server sends a fixed window — last week through next week — so this list is a dozen
        // rows at most and never needs paging. But sorted by date alone it opens on shifts the guard
        // has already worked, and buries the one they are about to. Today leads.
        val today = todayIso(clock.nowMillis())
        ScheduleUiState(
            upcoming = days.filter { it.date >= today }.sortedBy { it.date },
            past = days.filter { it.date < today }.sortedByDescending { it.date },
            today = today,
            linked = linked,
        )
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
fun ScheduleScreen(
    onOpenRound: (String) -> Unit = {},
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("My schedule") }

        when {
            // An admin error, and named as one. "You have no shifts" would be a different sentence
            // about a different problem, and would leave the guard waiting for a rota that is never
            // going to arrive.
            !state.linked -> item {
                Notice(
                    "Your account is not on the duty roster",
                    "Nobody has connected this login to a guard on the roster, so the app cannot " +
                        "see what you are scheduled for. Ask the office to link your account.",
                )
            }

            state.isEmpty -> item {
                Notice(
                    "No shifts downloaded yet",
                    "Open this screen while you have a connection and your roster will be saved " +
                        "to this phone.",
                )
            }

            else -> {
                item {
                    Text(
                        "Saved on this phone, so it works with no signal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(state.upcoming, key = { it.date }) { day ->
                    DayRow(
                        day = day,
                        isToday = day.date == state.today,
                        onOpenRound = { onOpenRound(day.date) },
                    )
                }

                // Shifts already worked. Kept, because a guard does check what they did last
                // Thursday — but kept below, because it is not what they came here to see.
                if (state.past.isNotEmpty()) {
                    item { GroupLabel("Earlier") }
                    items(state.past, key = { it.date }) { day ->
                        DayRow(
                            day = day,
                            isToday = false,
                            onOpenRound = { onOpenRound(day.date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * One rostered day.
 *
 * The two things a guard came here for are *when* — which day, and what hours — so those are what
 * the row leads with: a calendar-style date block, and the shift hours as the headline. The duty
 * code and the rule it implies are the supporting detail, not the other way round.
 */
@Composable
private fun DayRow(day: DutyAssignment, isToday: Boolean, onOpenRound: () -> Unit) {
    // Only a roving day has a round to open. A stationed guard has one post and the row already
    // tells them everything about it, so the card is not made to look tappable when it is not.
    val roving = day.dutyType == DutyType.ROVING
    GuardCard(modifier = if (roving) Modifier.clickable(onClick = onOpenRound) else Modifier) {
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

        if (roving) {
            Spacer(Modifier.height(8.dp))
            Text(
                "See the posts on this round  ›",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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

private fun todayIso(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

/** One piece of `2026-07-13` — `EEE` → `Mon`, `d` → `13`, `MMM` → `Jul`. */
private fun datePart(iso: String, pattern: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
    SimpleDateFormat(pattern, Locale.getDefault()).format(parsed)
}.getOrDefault(if (pattern == "d") iso.takeLast(2) else "")
