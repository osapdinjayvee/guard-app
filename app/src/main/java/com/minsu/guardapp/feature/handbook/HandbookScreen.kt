package com.minsu.guardapp.feature.handbook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.minsu.guardapp.core.media.DocumentCache
import com.minsu.guardapp.domain.repository.DocumentRepository
import com.minsu.guardapp.ui.components.GuardCard
import com.minsu.guardapp.ui.components.ScreenTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed interface HandbookState {
    data object Loading : HandbookState
    data class Ready(val pageCount: Int) : HandbookState
    data class Failed(val reason: String) : HandbookState
}

@HiltViewModel
class HandbookViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val cache: DocumentCache,
) : ViewModel() {

    private val _state = MutableStateFlow<HandbookState>(HandbookState.Loading)
    val state: StateFlow<HandbookState> = _state.asStateFlow()

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null

    /**
     * PdfRenderer opens one page at a time and is not safe to use from two places at once.
     *
     * The list renders pages as they scroll into view, which is several coroutines asking at the
     * same moment. Without this they trample each other's open page and the renderer throws.
     */
    private val lock = Mutex()

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        _state.value = HandbookState.Loading

        // The cached copy first, so a guard at a post with no signal still gets the handbook they
        // read last week. Only when nothing is held does this need the network at all.
        val identifier = DocumentRepository.GUARD_HANDBOOK
        val file = cache.cached(identifier)
            ?: documents.url(identifier)?.let { cache.fetch(identifier, it) }

        if (file == null) {
            _state.value = HandbookState.Failed(
                "The handbook is not on this phone yet. Connect to the internet and try again."
            )
            return@launch
        }

        _state.value = withContext(Dispatchers.IO) {
            runCatching {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val opened = PdfRenderer(fd)
                descriptor = fd
                renderer = opened
                HandbookState.Ready(opened.pageCount)
            }.getOrElse {
                // A stored file that will not open is worse than none: it would fail identically
                // every time this screen opened. Dropped so the next attempt re-fetches it.
                file.delete()
                HandbookState.Failed("The handbook could not be opened. Try again.")
            }
        }
    }

    /** One page, drawn at [widthPx]. Null when the page cannot be rendered. */
    suspend fun page(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        withContext(Dispatchers.IO) {
            val pdf = renderer ?: return@withContext null

            runCatching {
                pdf.openPage(index).use { page ->
                    val height = (widthPx.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    // PdfRenderer draws only ink; the paper is whatever was already in the bitmap,
                    // which is transparent. Left alone the text lands on a black page in dark mode.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }.getOrNull()
        }
    }

    override fun onCleared() {
        renderer?.close()
        descriptor?.close()
        renderer = null
        descriptor = null
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

        when (val current = state) {
            HandbookState.Loading -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Opening the handbook…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is HandbookState.Failed -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                GuardCard {
                    Text(
                        current.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::load,
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

            is HandbookState.Ready -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                items((0 until current.pageCount).toList(), key = { it }) { index ->
                    PdfPage(index = index, render = viewModel::page)
                }
            }
        }
    }
}

/**
 * One page, rendered when it scrolls into view and not before.
 *
 * Rendering the whole handbook up front would hold every page as a full-size bitmap at once, which
 * on a long document is tens of megabytes for pages nobody has looked at.
 */
@Composable
private fun PdfPage(index: Int, render: suspend (Int, Int) -> Bitmap?) {
    // A fixed width rather than the measured one: it decides the bitmap's size, and re-rendering
    // every page on each layout pass would be far more expensive than a page that is slightly
    // wider than the screen and scaled down to fit.
    val widthPx = 1080

    val bitmap by produceState<Bitmap?>(initialValue = null, index) {
        value = render(index, widthPx)
    }

    val image = bitmap
    if (image == null) {
        // Holds the scroll position steady while the page is drawn, so the list does not jump
        // under the guard's thumb as each one arrives.
        Spacer(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.414f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
    } else {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "Handbook page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}
