package com.minsu.guardapp.feature.reports

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.media.DocumentCache
import com.minsu.guardapp.core.media.PdfDocument
import com.minsu.guardapp.ui.components.PdfState
import com.minsu.guardapp.ui.components.PdfViewer
import com.minsu.guardapp.ui.components.ScreenTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DtrUiState(
    val document: PdfState = PdfState.Loading,
    /** `2026-08`, which is what the endpoint takes. */
    val month: String = "",
    /** `August 2026`, which is what a guard reads. */
    val monthLabel: String = "",
    /** False in the current month: there is no DTR for a month that has not started. */
    val canGoForward: Boolean = false,
)

@HiltViewModel
class DtrViewModel @Inject constructor(
    private val cache: DocumentCache,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DtrUiState())
    val uiState: StateFlow<DtrUiState> = _uiState.asStateFlow()

    private var document: PdfDocument? = null

    /** Months back from the current one. 0 is this month, which is where the screen opens. */
    private var offset = 0

    init {
        load()
    }

    fun previousMonth() {
        offset += 1
        load()
    }

    fun nextMonth() {
        if (offset == 0) return
        offset -= 1
        load()
    }

    fun load() = viewModelScope.launch {
        val month = monthAt(offset)

        _uiState.update {
            it.copy(
                document = PdfState.Loading,
                month = month,
                monthLabel = labelAt(offset),
                canGoForward = offset > 0,
            )
        }

        /*
         * Asked of the server first, and only then of the cache.
         *
         * The opposite of the handbook. A DTR is built from attendance that is still arriving and
         * still being corrected by the office, so the copy on the phone is a snapshot of what the
         * month looked like when it was last opened — worth showing when there is no signal, never
         * worth preferring when there is.
         */
        val identifier = "dtr-$month"
        val file = cache.fetchFromApi(identifier, "dtr?month=$month")
            ?: cache.cached(identifier)

        if (file == null) {
            _uiState.update {
                it.copy(
                    document = PdfState.Failed(
                        "Your DTR for ${labelAt(offset)} could not be downloaded, and this phone " +
                            "does not have a copy yet. Connect to the internet and try again."
                    )
                )
            }
            return@launch
        }

        document?.close()
        val opened = PdfDocument.open(file)
        document = opened

        _uiState.update {
            it.copy(
                document = if (opened == null) {
                    // A stored file that will not open fails identically every time. Dropped so
                    // the next attempt fetches a good one instead of showing the same error.
                    file.delete()
                    PdfState.Failed("Your DTR could not be opened. Try again.")
                } else {
                    PdfState.Ready(opened.pageCount)
                }
            )
        }
    }

    suspend fun page(index: Int, widthPx: Int): Bitmap? = document?.page(index, widthPx)

    override fun onCleared() {
        document?.close()
        document = null
    }

    private fun calendarAt(monthsBack: Int): Calendar = Calendar.getInstance().apply {
        timeInMillis = clock.nowMillis()
        add(Calendar.MONTH, -monthsBack)
    }

    private fun monthAt(monthsBack: Int): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date(calendarAt(monthsBack).timeInMillis))

    private fun labelAt(monthsBack: Int): String =
        SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            .format(Date(calendarAt(monthsBack).timeInMillis))
}

/**
 * The guard's own Daily Time Record, as the office issues it.
 *
 * Not the same document as Reports' export, and deliberately so: that one is built on this phone
 * from the records it happens to hold, and is useful for checking your own work. This is the one
 * the office signs, generated on the server from what it actually has — which is the version that
 * settles a disagreement about a shift.
 */
@Composable
fun DtrScreen(onBack: () -> Unit, viewModel: DtrViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        ScreenTitle("My DTR", onBack = onBack)

        MonthPicker(
            label = state.monthLabel,
            canGoForward = state.canGoForward,
            onPrevious = viewModel::previousMonth,
            onNext = viewModel::nextMonth,
        )

        Spacer(Modifier.height(12.dp))

        PdfViewer(
            state = state.document,
            label = "DTR",
            onRetry = { viewModel.load() },
            render = viewModel::page,
            loadingText = "Getting your DTR for ${state.monthLabel}…",
        )
    }
}

@Composable
private fun MonthPicker(
    label: String,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrevious) {
            Text(
                "‹",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // Dimmed rather than removed in the current month, so the control does not jump about as
        // the guard steps back and forward through the year.
        IconButton(onClick = onNext, enabled = canGoForward) {
            Text(
                "›",
                style = MaterialTheme.typography.headlineMedium,
                color = if (canGoForward) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
