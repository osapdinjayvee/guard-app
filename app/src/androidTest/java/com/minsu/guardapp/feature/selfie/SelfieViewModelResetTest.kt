package com.minsu.guardapp.feature.selfie

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minsu.guardapp.core.camera.SelfieCapture
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.location.LocationFix
import com.minsu.guardapp.core.location.LocationProvider
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.domain.model.EvaluationQuestion
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.DutyRepository
import com.minsu.guardapp.domain.repository.EvaluationRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The record-loss regression the UAT surfaced: *"hindi nanghingi ng selfie, basta lumabas ang
 * recorded, wala naman sa reports."*
 *
 * [SelfieViewModel] is scoped to the Scan tab and outlives any one capture, so a finished attendance
 * leaves `submitted = true` sitting in the retained instance. On the next capture the screen renders
 * that stale terminal state for a frame — a false "Attendance recorded" with no camera — which a
 * guard dismisses, recording nothing. These tests pin the two seams that close it: [SelfieViewModel.reset]
 * blanks the terminal state on dismiss, and [SelfieViewModel.start] begins a genuinely fresh session
 * for a new attendance while leaving an in-progress one alone on a bare recomposition.
 *
 * Instrumented rather than a plain unit test only because [SelfieCapture] is a final class that needs
 * a real [android.content.Context]; nothing here touches the camera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SelfieViewModelResetTest {

    private val gateA = Checkpoint(1, "GATE-A", "Main Gate", isActive = true, latitude = null, longitude = null)
    private val clinic = Checkpoint(2, "CLINIC", "Clinic", isActive = true, latitude = null, longitude = null)

    // Set Main to an unconfined test dispatcher so the ViewModel's launches run eagerly on this
    // thread up to their first suspension. Virtual time is never advanced, so the live-clock and
    // GPS-stream loops simply park at their first delay/await — reset() cancels them at the end.
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun reset_blanks_the_state_so_a_finished_attendance_is_not_replayed() {
        val vm = viewModel()
        vm.start(gateA, AttendanceType.TIME_IN)
        assertEquals("start must populate the session", gateA, vm.uiState.value.checkpoint)

        vm.reset()

        val state = vm.uiState.value
        assertNull("the checkpoint must be cleared", state.checkpoint)
        assertNull("the type must be cleared", state.type)
        assertNull("no captured photo may linger", state.capturedFile)
        assertFalse("the stale 'recorded' screen must be gone", state.submitted)
        assertFalse("nothing may be mid-submit", state.isSubmitting)
        // A blank session is back to acquiring GPS, exactly as a fresh screen would be.
        assertTrue("a fresh session is acquiring again", state.isAcquiringGps)
    }

    /** A new attendance must not inherit the previous one's checkpoint or type. */
    @Test
    fun starting_a_new_attendance_clears_the_previous_one() {
        val vm = viewModel()
        vm.start(gateA, AttendanceType.TIME_IN)

        vm.start(clinic, AttendanceType.TIME_OUT)

        val state = vm.uiState.value
        assertEquals(clinic, state.checkpoint)
        assertEquals(AttendanceType.TIME_OUT, state.type)
        assertFalse(state.submitted)
        vm.reset() // stop the session's live-clock and GPS-stream loops
    }

    /**
     * The other half of the same guard: a bare recomposition of the *same* attendance must not wipe
     * work in progress. [SelfieScreen]'s `LaunchedEffect` re-invokes start() on re-entry, and if that
     * reset the state a guard would lose a half-answered evaluation every time the screen recomposed.
     */
    @Test
    fun re_entering_the_same_attendance_keeps_answers_in_progress() {
        val vm = viewModel()
        vm.start(gateA, AttendanceType.TIME_OUT)
        vm.answer(questionId = 1L, answer = true)

        vm.start(gateA, AttendanceType.TIME_OUT) // same checkpoint + type: a recomposition, not a new scan

        assertEquals("an in-progress answer must survive the re-entry", true, vm.uiState.value.answers[1L])
        vm.reset()
    }

    private fun viewModel() = SelfieViewModel(
        profiles = FakeProfiles(GuardProfile(7, "Juan Dela Cruz", "guard01")),
        settings = FakeSettings(),
        duties = FakeDuties(),
        evaluations = FakeEvaluations(),
        attendance = FakeAttendance(),
        location = FakeLocation(),
        selfieCapture = SelfieCapture(ApplicationProvider.getApplicationContext()),
        clock = Clock { 1_783_663_331_000L },
    )

    private class FakeProfiles(private val profile: GuardProfile?) : ProfileRepository {
        override fun observe(): Flow<GuardProfile?> = MutableStateFlow(profile)
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
        override suspend fun clear() = Unit
    }

    private class FakeSettings : SettingsRepository {
        override fun observe(): Flow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun current(): AppSettings = AppSettings()
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    private class FakeDuties : DutyRepository {
        override suspend fun activeDuty(): Duty? = null
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    private class FakeEvaluations : EvaluationRepository {
        override suspend fun questions(): List<EvaluationQuestion> = emptyList()
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    /** A never-emitting fix stream (empty, completes at once) so no GPS coroutine is left running. */
    private class FakeLocation : LocationProvider {
        override suspend fun currentFix(timeoutMillis: Long): LocationFix? = null
        override suspend fun lastKnownFix(): LocationFix? = null
        override fun stream(intervalMillis: Long): Flow<LocationFix> = emptyFlow()
    }

    private class FakeAttendance : AttendanceRepository {
        override fun observeUnsyncedCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeOtherAccountUnsyncedCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeHistory(limit: Int): Flow<List<AttendanceRecord>> = MutableStateFlow(emptyList())
        override fun observeRecord(id: String): Flow<AttendanceRecord?> = MutableStateFlow(null)
        override suspend fun retry(id: String) = Unit
        override fun observeStuckCount(): Flow<Int> = MutableStateFlow(0)
        override suspend fun syncNow(): Int = 0
        override fun observeInRange(fromMillis: Long, toMillis: Long): Flow<List<AttendanceRecord>> =
            MutableStateFlow(emptyList())
        override suspend fun checkpointVisitsToday(): Map<Long, Int> = emptyMap()
        override suspend fun lastVisitedCheckpointToday(): Long? = null
        override suspend fun submit(id: String, draft: AttendanceDraft) = Unit
    }
}
