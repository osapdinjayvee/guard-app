package com.minsu.guardapp.feature.reports

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.media.DocumentCache
import com.minsu.guardapp.core.media.PdfDocument
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.ui.components.PdfState
import com.minsu.guardapp.ui.components.PdfViewer
import com.minsu.guardapp.ui.components.ScreenTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class GuardReportUiState(
    val document: PdfState = PdfState.Loading,
    /** `1 Aug – 15 Aug 2026`, for the line under the title. */
    val rangeLabel: String = "",
)

@HiltViewModel
class GuardReportViewModel @Inject constructor(
    private val cache: DocumentCache,
    private val profiles: ProfileRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The range the guard already chose on Reports, carried through the route. */
    private val startDate: String = savedStateHandle["start"] ?: ""
    private val endDate: String = savedStateHandle["end"] ?: ""

    private val _uiState = MutableStateFlow(GuardReportUiState())
    val uiState: StateFlow<GuardReportUiState> = _uiState.asStateFlow()

    private var document: PdfDocument? = null

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        _uiState.update {
            it.copy(document = PdfState.Loading, rangeLabel = readableRange())
        }

        /*
         * The endpoint wants a user id, unlike the DTR which reads it from the token.
         *
         * Taken from the cached profile rather than asked for, so this still opens at a post with
         * no signal — and the server checks the token anyway, so a guard cannot see somebody
         * else's report by knowing their number.
         */
        val userId = profiles.observe().first()?.id

        if (userId == null) {
            _uiState.update {
                it.copy(
                    document = PdfState.Failed(
                        "This phone does not know who is signed in yet. Open Home once with a " +
                            "connection, then try again."
                    )
                )
            }
            return@launch
        }

        // Built from attendance the office may still be correcting, so the server is asked first
        // and the copy on the phone is only the fallback. Same reasoning as the DTR.
        val identifier = "guard-report-$userId-$startDate-$endDate"
        val file = cache.fetchFromApi(
            identifier,
            "guard-report?user_id=$userId&start_date=$startDate&end_date=$endDate",
        ) ?: cache.cached(identifier)

        if (file == null) {
            _uiState.update {
                it.copy(
                    document = PdfState.Failed(
                        "The report for ${readableRange()} could not be downloaded. Connect to " +
                            "the internet and try again."
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
                    file.delete()
                    PdfState.Failed("The report could not be opened. Try again.")
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

    private fun readableRange(): String {
        val from = startDate.asReadableDate()
        val to = endDate.asReadableDate()
        return if (from == to) from else "$from – $to"
    }
}

/** `2026-08-01` as `1 Aug 2026`. The date is left alone if it will not parse. */
internal fun String.asReadableDate(): String = runCatching {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(this)!!
        .let { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(it) }
}.getOrDefault(this)

/**
 * The office's report for a chosen range, as the server builds it.
 *
 * Distinct from the DTR, which is a month at a time and is the record of hours worked. This one
 * covers whatever range the guard picked on Reports and carries the checkpoint columns, which is
 * what the security office reads when it wants to know how a fortnight's rounds actually went.
 */
@Composable
fun GuardReportScreen(onBack: () -> Unit, viewModel: GuardReportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        ScreenTitle("Guard report", onBack = onBack)

        Text(
            state.rangeLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        PdfViewer(
            state = state.document,
            label = "Report",
            onRetry = { viewModel.load() },
            render = viewModel::page,
            loadingText = "Getting your report…",
        )
    }
}
