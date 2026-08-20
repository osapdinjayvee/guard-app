package com.minsu.guardapp.feature.handbook

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.media.DocumentCache
import com.minsu.guardapp.core.media.PdfDocument
import com.minsu.guardapp.domain.repository.DocumentRepository
import com.minsu.guardapp.ui.components.PdfState
import com.minsu.guardapp.ui.components.PdfViewer
import com.minsu.guardapp.ui.components.ScreenTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HandbookViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val cache: DocumentCache,
) : ViewModel() {

    private val _state = MutableStateFlow<PdfState>(PdfState.Loading)
    val state: StateFlow<PdfState> = _state.asStateFlow()

    private var document: PdfDocument? = null

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        _state.value = PdfState.Loading

        // The cached copy first, so a guard at a post with no signal still gets the handbook they
        // read last week. Only when nothing is held does this need the network at all.
        //
        // The opposite order to the DTR and the report, and for a reason: the handbook is an
        // edition the office publishes, not a document rebuilt from this week's attendance.
        val identifier = DocumentRepository.GUARD_HANDBOOK
        val file = cache.cached(identifier)
            ?: documents.url(identifier)?.let { cache.fetch(identifier, it) }

        if (file == null) {
            _state.value = PdfState.Failed(
                "The handbook is not on this phone yet. Connect to the internet and try again."
            )
            return@launch
        }

        document?.close()
        val opened = PdfDocument.open(file)
        document = opened

        _state.value = if (opened == null) {
            // A stored file that will not open is worse than none: it would fail identically
            // every time this screen opened. Dropped so the next attempt re-fetches it.
            file.delete()
            PdfState.Failed("The handbook could not be opened. Try again.")
        } else {
            PdfState.Ready(opened.pageCount)
        }
    }

    suspend fun page(index: Int, widthPx: Int): Bitmap? = document?.page(index, widthPx)

    override fun onCleared() {
        document?.close()
        document = null
    }
}

/**
 * The guard handbook, read inside the app.
 *
 * Rendered here rather than handed to whatever the phone uses for PDFs. A guard sent out to another
 * app loses the back arrow, the bottom bar, and sometimes their place in what they were doing —
 * and on a handset with no PDF reader at all, the tile simply did nothing.
 */
@Composable
fun HandbookScreen(onBack: () -> Unit, viewModel: HandbookViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        ScreenTitle("Guard Handbook", onBack = onBack)

        PdfViewer(
            state = state,
            label = "Handbook",
            onRetry = { viewModel.load() },
            render = viewModel::page,
            loadingText = "Opening the handbook…",
        )
    }
}
