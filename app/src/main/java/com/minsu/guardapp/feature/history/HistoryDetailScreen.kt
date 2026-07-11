package com.minsu.guardapp.feature.history

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.SyncState
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.feature.home.SyncBadge
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.ScreenTitle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltViewModel(assistedFactory = HistoryDetailViewModel.Factory::class)
class HistoryDetailViewModel @AssistedInject constructor(
    @Assisted private val recordId: String,
    private val attendance: AttendanceRepository,
) : ViewModel() {

    val record: StateFlow<AttendanceRecord?> = attendance.observeRecord(recordId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun retry() = viewModelScope.launch { attendance.retry(recordId) }

    @AssistedFactory
    interface Factory {
        fun create(recordId: String): HistoryDetailViewModel
    }
}

@Composable
fun HistoryDetailScreen(recordId: String, onBack: () -> Unit) {
    val viewModel: HistoryDetailViewModel =
        hiltViewModel<HistoryDetailViewModel, HistoryDetailViewModel.Factory>(
            creationCallback = { factory -> factory.create(recordId) },
        )
    val record by viewModel.record.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenTitle("Attendance")

        val current = record ?: return@Column

        Selfie(current.selfiePath)
        Spacer(Modifier.height(16.dp))

        GuardCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current.type.name.replace('_', ' '),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SyncBadge(current.syncState)
            }
            Spacer(Modifier.height(12.dp))
            Field("Checkpoint", current.checkpointCode)
            Field("Captured", fullTimestamp(current.capturedAt))
            Field(
                "Location",
                if (current.latitude != null && current.longitude != null) {
                    "%.6f, %.6f".format(current.latitude, current.longitude)
                } else {
                    "Not recorded"
                },
            )
            current.accuracyMetres?.let { Field("GPS accuracy", "±${it.toInt()} m") }
        }

        if (current.syncState == SyncState.FAILED || current.syncState == SyncState.REJECTED) {
            Spacer(Modifier.height(16.dp))
            RetryCard(record = current, onRetry = viewModel::retry)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onBack,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text("Back to history", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Selfie(path: String) {
    val bitmap = remember(path) {
        File(path).takeIf(File::exists)?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
    }
    if (bitmap == null) {
        GuardCard { Text("The photo for this record is no longer on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "Attendance photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(24.dp)),
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RetryCard(record: AttendanceRecord, onRetry: () -> Unit) {
    GuardCard {
        Text(
            if (record.syncState == SyncState.REJECTED) "Rejected by the server" else "Upload failed",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        record.lastError?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("Try again", fontWeight = FontWeight.Bold)
        }
    }
}

private fun fullTimestamp(millis: Long): String =
    SimpleDateFormat("d MMM yyyy · h:mm:ss a", Locale.getDefault()).format(Date(millis))
