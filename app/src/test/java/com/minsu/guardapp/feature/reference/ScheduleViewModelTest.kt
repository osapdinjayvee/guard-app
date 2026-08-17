package com.minsu.guardapp.feature.reference

import com.minsu.guardapp.domain.model.ShiftWindow
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.repository.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The roster arrives sorted by date, which puts last week's finished shifts at the top and the
     * shift the guard is about to work halfway down the screen. Today must lead.
     */
    @Test
    fun `today leads, then the days ahead, with worked shifts below`() = runTest {
        val state = state(
            days = listOf(
                day("2026-07-15"),
                day("2026-07-10"),
                day("2026-07-13"), // today
                day("2026-07-11"),
                day("2026-07-14"),
            ),
        )

        assertEquals(
            listOf("2026-07-13", "2026-07-14", "2026-07-15"),
            state.upcoming.map { it.date },
        )
        // Newest first: last night's shift is the one a guard is most likely to be checking.
        assertEquals(listOf("2026-07-11", "2026-07-10"), state.past.map { it.date })
        assertEquals("2026-07-13", state.today)
    }

    /** A rest day today is not an empty roster. The days ahead still have to show. */
    @Test
    fun `a rest day today still lists the days ahead`() = runTest {
        val state = state(days = listOf(day("2026-07-12"), day("2026-07-16")))

        assertEquals(listOf("2026-07-16"), state.upcoming.map { it.date })
        assertEquals(listOf("2026-07-12"), state.past.map { it.date })
        assertTrue(state.upcoming.none { it.date == state.today })
    }

    @Test
    fun `no cached roster reports empty`() = runTest {
        assertTrue(state(days = emptyList()).isEmpty)
    }

    /**
     * The state is a `WhileSubscribed` flow, so it stays at its initial value until something
     * collects it. A test that only reads `.value` sees an empty roster and passes for the wrong
     * reason — or, as here, fails for one. Subscribe first.
     */
    private fun TestScope.state(days: List<DutyAssignment>): ScheduleUiState {
        val viewModel = ScheduleViewModel(FakeSchedule(days), Clock { TODAY_MILLIS })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel.uiState.value
    }

    private fun day(date: String) = DutyAssignment(
        date = date,
        dutyType = DutyType.ROVING,
        dutyName = "Roving Guard",
        startsAt = "23:00:00",
        endsAt = "07:00:00",
        totalHours = 8f,
    )

    private class FakeSchedule(private val days: List<DutyAssignment>) : ScheduleRepository {
        override fun observeCurrentDuty(): Flow<DutyAssignment?> = MutableStateFlow(null)
        override fun observeAll(): Flow<List<DutyAssignment>> = MutableStateFlow(days)
        override suspend fun currentDuty(): DutyAssignment? = null
        override val isLinked: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun postTimedInAt(window: ShiftWindow): Long? = null
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    private companion object {
        /** 2026-07-13, midday, in the device's own zone — so the date lands on the 13th anywhere. */
        val TODAY_MILLIS: Long = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .parse("2026-07-13 12:00")!!
            .time
    }
}
