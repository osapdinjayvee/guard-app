package com.minsu.guardapp.feature.reference

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.media.SelfieStore
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.SyncState
import com.minsu.guardapp.domain.model.attendanceWindowOn
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.ScheduleRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.ScreenTitle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CheckpointVisitsViewModel.Factory::class)
class CheckpointVisitsViewModel @AssistedInject constructor(
    // Both are Strings, so Dagger needs them named or it cannot tell which is which — and a
    // silent swap here would show the wrong post's visits under the right post's title.
    @Assisted("code") private val checkpointCode: String,
    @Assisted("date") private val date: String,
    attendance: AttendanceRepository,
    schedule: ScheduleRepository,
    settings: SettingsRepository,
    private val selfies: SelfieStore,
) : ViewModel() {

    /**
     * This post's visits on this date, newest first.
     *
     * Read from the phone, not the server: a guard checking what they have already done is often
     * standing at the post with no signal, and a record still queued for upload counts — the scan
     * happened and the photograph exists whether or not anyone has received it yet.
     */
    val visits: StateFlow<List<AttendanceRecord>> =
        // The shift filed for this date, not the date's own midnights — the same bound the round
        // this screen is opened from uses, so the count there and the photographs here cannot
        // disagree about which visits belong to the night the guard actually worked.
        combine(schedule.observeAll(), settings.observe()) { duties, config ->
            duties.attendanceWindowOn(
                date = date,
                earlyMinutes = config.timeInEarlyMinutes,
                graceMinutes = config.shiftCloseGraceMinutes,
            )
        }
            .distinctUntilChanged()
            .flatMapLatest { window -> attendance.observeInRange(window.start, window.end + 1) }
            .map { records ->
                records
                    .filter {
                        it.type == AttendanceType.CHECKPOINT &&
                            it.checkpointCode.equals(checkpointCode, ignoreCase = true)
                    }
                    .sortedByDescending { it.capturedAt }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun selfie(record: AttendanceRecord): File? =
        selfies.resolve(record.id, record.selfiePath)

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("code") checkpointCode: String,
            @Assisted("date") date: String,
        ): CheckpointVisitsViewModel
    }
}

/**
 * Every visit to one post on one shift, with the photograph taken at each.
 *
 * The count on the round says a post has been reached twice. This is what those two visits actually
 * were — the times, and the face and place burned into each frame. It is the difference between a
 * number a guard is asked to trust and the evidence behind it.
 */
@Composable
fun CheckpointVisitsScreen(
    checkpointCode: String,
    checkpointName: String,
    date: String,
    onBack: () -> Unit,
    onShowMap: (() -> Unit)? = null,
) {
    val viewModel: CheckpointVisitsViewModel =
        hiltViewModel<CheckpointVisitsViewModel, CheckpointVisitsViewModel.Factory>(
            // Keyed, for the same reason the attendance detail is: this screen is opened from
            // inside another rather than navigated to, so without a key every post after the first
            // would be handed the first one's ViewModel.
            key = "$checkpointCode#$date",
            creationCallback = { factory -> factory.create(checkpointCode, date) },
        )
    val visits by viewModel.visits.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle(checkpointName, onBack = onBack) }

        item {
            GuardCard {
                Text(
                    checkpointCode,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when (visits.size) {
                        0 -> "Not visited yet this shift"
                        1 -> "1 visit this shift"
                        else -> "${visits.size} visits this shift"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (onShowMap != null) {
                    TextButton(onClick = onShowMap, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Show on map")
                    }
                }
            }
        }

        if (visits.isEmpty()) {
            item {
                GuardCard {
                    Text(
                        "Scan the code at this post to record a visit. The photo you take is kept " +
                            "with it, and appears here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(visits, key = { it.id }) { visit ->
            VisitCard(visit = visit, resolve = viewModel::selfie)
        }
    }
}

@Composable
private fun VisitCard(visit: AttendanceRecord, resolve: suspend (AttendanceRecord) -> File?) {
    GuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                visitTime(visit.capturedAt),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // Said here as well as in History, because a visit that has not reached the server is
            // still a visit — and a guard is entitled to know which of theirs are still owed.
            Text(
                when (visit.syncState) {
                    SyncState.SYNCED -> "Uploaded"
                    SyncState.REJECTED -> "Refused"
                    SyncState.FAILED -> "Not uploaded"
                    else -> "Waiting to upload"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (visit.syncState == SyncState.REJECTED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        VisitSelfie(visit = visit, resolve = resolve)
    }
}

/**
 * The photograph for one visit, fetched when the card scrolls into view.
 *
 * A record restored from the server carries the URL rather than a file, so this may be a download.
 * Doing them all up front would spend a guard's data on a shift they only wanted the count of.
 */
@Composable
private fun VisitSelfie(visit: AttendanceRecord, resolve: suspend (AttendanceRecord) -> File?) {
    val file by produceState<File?>(initialValue = null, visit.id) { value = resolve(visit) }

    val bitmap = file?.let { image ->
        androidx.compose.runtime.remember(image.path, image.lastModified()) {
            BitmapFactory.decodeFile(image.absolutePath)?.asImageBitmap()
        }
    }

    if (bitmap == null) {
        // The same frame the photo will occupy, so the card does not grow under the guard's thumb
        // as each visit's image arrives.
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (file == null) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
            }
            Text(
                if (file == null) "Loading the photo…" else "The photo is not on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "Visit photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(16.dp)),
        )
    }
}

/** `2:14 PM` — the clock a guard reads, not a timestamp. */
private fun visitTime(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(java.util.Date(millis))

