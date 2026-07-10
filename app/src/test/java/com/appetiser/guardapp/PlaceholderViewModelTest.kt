package com.appetiser.guardapp

import com.appetiser.guardapp.core.common.Clock
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderViewModelTest {

    private class FakeClock(var now: Long) : Clock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun `reads the time from the injected clock, not the system clock`() {
        val clock = FakeClock(now = 1_700_000_000_000)

        val viewModel = PlaceholderViewModel(clock)

        assertEquals(1_700_000_000_000, viewModel.startedAtMillis)
    }
}
