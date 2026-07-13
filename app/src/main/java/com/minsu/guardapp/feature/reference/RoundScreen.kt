package com.minsu.guardapp.feature.reference

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.ScreenTitle
import com.minsu.guardapp.ui.theme.SyncSynced
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** One post on the round, and whether the guard has been to it on this date. */
data class Stop(
    val checkpoint: Checkpoint,
    /** Null until the guard scans it. */
    val visitedAt: Long?,
    val record: AttendanceRecord?,
) {
    val isDone: Boolean get() = visitedAt != null
}

data class RoundUiState(
    val date: String = "",
    val stops: List<Stop> = emptyList(),
    val timedInAt: Long? = null,
    val timedOutAt: Long? = null,
) {
    val done: Int get() = stops.count { it.isDone }
    val total: Int get() = stops.size
}

/**
 * A roving guard's round for one date, assembled entirely from what is already on the phone: the
 * cached checkpoint list, and the attendance records this device holds for that day.
 *
 * That matters more than it sounds. A guard doing a perimeter round at 3am has no signal, and the
 * question they are asking — *which posts have I already done* — is exactly the one they cannot
 * answer from memory at the end of an eight-hour shift. A record that is still queued for upload
 * counts as done here, because it is: the scan happened, and the evidence is saved.
 */
@HiltViewModel
class RoundViewModel @Inject constructor(
    checkpoints: CheckpointRepository,
    attendance: AttendanceRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val date: String = savedStateHandle["date"] ?: ""

    val uiState: StateFlow<RoundUiState> = run {
        val from = startOfDay(date)
        combine(
            checkpoints.observeActive(),
            attendance.observeInRange(from, from + DAY_MILLIS),
        ) { posts, records ->
            RoundUiState(
                date = date,
                stops = posts.map { post ->
                    // A checkpoint scan on a roving round. Time In and Time Out are the bookends of
                    // the shift, not stops on it, so they do not tick a post off.
                    val visit = records.firstOrNull {
                        it.type == AttendanceType.CHECKPOINT &&
                            it.checkpointCode.equals(post.code, ignoreCase = true)
                    }
                    Stop(checkpoint = post, visitedAt = visit?.capturedAt, record = visit)
                },
                timedInAt = records.firstOrNull { it.type == AttendanceType.TIME_IN }?.capturedAt,
                timedOutAt = records.firstOrNull { it.type == AttendanceType.TIME_OUT }?.capturedAt,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoundUiState(date = date))
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

@Composable
fun RoundScreen(viewModel: RoundViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("My round") }

        item { ProgressCard(state) }

        item { Bookend("Time In", state.timedInAt, "Scan any post to start your shift") }

        items(state.stops, key = { it.checkpoint.id }) { stop -> StopRow(stop) }

        item { Bookend("Time Out", state.timedOutAt, "Scan to end your shift") }
    }
}

@Composable
private fun ProgressCard(state: RoundUiState) {
    GuardCard {
        Text(
            longDate(state.date),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "${state.done} of ${state.total} posts scanned",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Counted from this phone, so it is right with no signal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StopRow(stop: Stop) {
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(done = stop.isDone)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stop.checkpoint.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stop.checkpoint.code,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stop.visitedAt?.let(::clockTime) ?: "Not yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (stop.isDone) SyncSynced else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Time In and Time Out — the shift's bookends, drawn apart from the posts because they are not posts. */
@Composable
private fun Bookend(label: String, at: Long?, hint: String) {
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(done = at != null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (at == null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                at?.let(::clockTime) ?: "Not yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (at != null) SyncSynced else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusDot(done: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (done) SyncSynced else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Text(
                if (done) "✓" else "•",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (done) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** Local midnight on [iso]. Falls back to the epoch, which shows an empty round rather than crashing. */
private fun startOfDay(iso: String): Long = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
    Calendar.getInstance().apply {
        time = parsed
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}.getOrDefault(0L)

private fun longDate(iso: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
    SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(parsed).uppercase()
}.getOrDefault(iso)

private fun clockTime(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
