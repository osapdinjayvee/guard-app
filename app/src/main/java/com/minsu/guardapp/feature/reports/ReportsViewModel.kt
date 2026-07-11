package com.minsu.guardapp.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

data class ReportsUiState(
    val range: ReportRange = ReportRange.DAILY,
    val window: ReportWindow = ReportWindow(0, 0),
    val records: List<AttendanceRecord> = emptyList(),
    val totals: ReportTotals = ReportTotals(0, 0),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val attendance: AttendanceRepository,
    private val profiles: ProfileRepository,
    private val pdfWriter: ReportPdfWriter,
    private val clock: Clock,
) : ViewModel() {

    private val selectedRange = MutableStateFlow(ReportRange.DAILY)

    val uiState: StateFlow<ReportsUiState> =
        selectedRange.flatMapLatest { range ->
            val window = ReportRanges.windowFor(range, now = clock.nowMillis())
            attendance.observeInRange(window.fromMillis, window.toMillis).map { records ->
                ReportsUiState(range, window, records, ReportRanges.totalsOf(records))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    fun selectRange(range: ReportRange) {
        selectedRange.value = range
    }

    /** Renders the current report to a PDF and returns the file for the caller to share. */
    suspend fun exportPdf(): File {
        val state = uiState.value
        val guardName = profiles.observe().first()?.name.orEmpty()
        return pdfWriter.write(
            guardName = guardName.ifBlank { "Guard" },
            rangeLabel = state.range.name.lowercase().replaceFirstChar { it.uppercase() },
            window = state.window,
            records = state.records,
            totals = state.totals,
            generatedAtMillis = clock.nowMillis(),
        )
    }
}
