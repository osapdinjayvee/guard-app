package com.appetiser.guardapp.feature.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appetiser.guardapp.R
import com.appetiser.guardapp.ui.components.EmptyState
import com.appetiser.guardapp.ui.components.ScreenTitle

/**
 * Daily, weekly, monthly and custom-range summaries with PDF export land in T-30/T-31.
 *
 * Whether these read from Room or from `GET /api/attendance/report` is still an open product
 * decision — see `.docs/api/DECISIONS.md`.
 */
@Composable
fun ReportsScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        ScreenTitle("Reports")
        EmptyState(
            iconRes = R.drawable.ic_grades,
            title = "No reports yet",
            body = "Daily, weekly and monthly summaries will appear here",
            modifier = Modifier.padding(bottom = 80.dp),
        )
    }
}
