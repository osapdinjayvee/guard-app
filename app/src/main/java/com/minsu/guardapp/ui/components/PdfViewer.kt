package com.minsu.guardapp.ui.components

import android.graphics.Bitmap
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

/** What a document screen is showing. Shared by the handbook, the DTR and the guard report. */
sealed interface PdfState {
    data object Loading : PdfState
    data class Ready(val pageCount: Int) : PdfState
    data class Failed(val reason: String) : PdfState
}

/**
 * A PDF, page by page, with its own loading and failure states.
 *
 * @param label what the pages are, for the screen reader — "DTR page 2 of 3".
 * @param render draws one page at the given width; supplied by the screen's view model.
 */
@Composable
fun PdfViewer(
    state: PdfState,
    label: String,
    onRetry: () -> Unit,
    render: suspend (Int, Int) -> Bitmap?,
    loadingText: String = "Opening…",
) {
    when (state) {
        PdfState.Loading -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(
                loadingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is PdfState.Failed -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            GuardCard {
                Text(
                    state.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

        is PdfState.Ready -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 16.dp),
        ) {
            items((0 until state.pageCount).toList(), key = { it }) { index ->
                PdfPage(
                    index = index,
                    render = render,
                    contentDescription = "$label page ${index + 1} of ${state.pageCount}",
                )
            }
        }
    }
}

/**
 * One page, rendered when it scrolls into view and not before.
 *
 * Rendering a whole document up front would hold every page as a full-size bitmap at once, which
 * on a long one is tens of megabytes for pages nobody has looked at.
 */
@Composable
private fun PdfPage(index: Int, render: suspend (Int, Int) -> Bitmap?, contentDescription: String) {
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
            contentDescription = contentDescription,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}
