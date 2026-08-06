package com.minsu.guardapp.feature.reports

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.SyncState
import com.minsu.guardapp.feature.history.HistoryDetailScreen
import com.minsu.guardapp.feature.home.SyncBadge
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.ScreenTitle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReportsScreen(viewModel: ReportsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Tapping a row opens the shared detail — the one place the rejection reason and a "Try again"
    // live. A nested route rather than a scaffold destination, so the bottom bar stays on Reports
    // while it is open. Mirrors History; survives rotation via rememberSaveable.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    selectedId?.let { id ->
        HistoryDetailScreen(recordId = id, onBack = { selectedId = null })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        ScreenTitle("Reports")

        RangeChips(selected = state.range, onSelect = viewModel::selectRange)
        Spacer(Modifier.height(16.dp))

        TotalsCard(state.totals)
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    val file = viewModel.exportPdf()
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Share report"))
                }
            },
            enabled = state.records.isNotEmpty(),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text("Export PDF", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.records, key = { it.id }) { record ->
                ReportRow(record, onClick = { selectedId = record.id })
            }
        }
    }
}

@Composable
private fun RangeChips(selected: ReportRange, onSelect: (ReportRange) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReportRange.entries.forEach { range ->
            FilterChip(
                selected = range == selected,
                onClick = { onSelect(range) },
                label = { Text(range.name.lowercase().replaceFirstChar { it.uppercase() }) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
private fun TotalsCard(totals: ReportTotals) {
    GuardCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Time In", totals.timeIn)
            Stat("Time Out", totals.timeOut)
            // Shown only to a guard who actually walks a round. A stationed guard records no
            // visits, and a permanent "Visits 0" beside their shift is a column about somebody
            // else's job.
            if (totals.visits > 0) Stat("Visits", totals.visits)
            Stat("Total", totals.total)
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReportRow(record: AttendanceRecord, onClick: () -> Unit) {
    GuardCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${record.type.name.replace('_', ' ')} · ${record.checkpointCode}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    timestamp(record.capturedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A bare red "Rejected" tells the guard nothing they can act on. When the server
                // gave a reason, show it here — and the whole row taps through to a detail with a
                // "Try again". A rejection the guard cannot see the cause of is the exact complaint
                // this row exists to answer.
                if (record.syncState == SyncState.REJECTED || record.syncState == SyncState.FAILED) {
                    record.lastError?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            SyncBadge(record.syncState)
        }
    }
}

private fun timestamp(millis: Long): String =
    SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(millis))
