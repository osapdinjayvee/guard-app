package com.minsu.guardapp.feature.reference

import androidx.lifecycle.SavedStateHandle
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.model.SyncOutcome
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.model.SyncState
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoundViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * One visit is not a finished post. The round is every post, the required number of times, and a
     * post visited once must not look identical to one that is done.
     */
    @Test
    fun `a post needs all its visits before it counts as done`() = runTest {
        val state = state(
            posts = listOf(post(1, "CP-MAIN-GATE"), post(2, "CP-LIBRARY")),
            records = listOf(
                record(AttendanceType.CHECKPOINT, "CP-MAIN-GATE", at = 1_000L),
                record(AttendanceType.CHECKPOINT, "CP-MAIN-GATE", at = 2_000L, id = "second"),
                record(AttendanceType.CHECKPOINT, "CP-LIBRARY", at = 1_500L),
            ),
        )

        val gate = state.stops.first { it.checkpoint.code == "CP-MAIN-GATE" }
        val library = state.stops.first { it.checkpoint.code == "CP-LIBRARY" }

        assertEquals(2, gate.visits)
        assertTrue(gate.isDone)
        assertEquals(2_000L, gate.lastVisitedAt)

        assertEquals(1, library.visits)
        assertFalse(library.isDone)

        assertEquals(1, state.done)
        assertEquals(2, state.total)
    }

    /**
     * Time In and Time Out happen *at* a checkpoint, but they are the bookends of the shift, not
     * stops on the round. If they ticked a post off, a guard who timed in at the Main Gate would be
     * told they had already patrolled it — and would skip it.
     */
    @Test
    fun `timing in at a post does not scan that post`() = runTest {
        val state = state(
            posts = listOf(post(1, "CP-MAIN-GATE")),
            records = listOf(record(AttendanceType.TIME_IN, "CP-MAIN-GATE", at = 500L)),
        )

        assertEquals(0, state.done)
        assertEquals(0, state.stops.single().visits)
        assertNull(state.stops.single().lastVisitedAt)
        assertEquals(500L, state.timedInAt)
    }

    /** A record still queued for upload is a scan that happened. The round does not wait on a server. */
    @Test
    fun `an unsynced scan still counts`() = runTest {
        val state = state(
            posts = listOf(post(1, "CP-CLINIC")),
            records = listOf(
                record(AttendanceType.CHECKPOINT, "CP-CLINIC", at = 900L, sync = SyncState.PENDING),
                record(AttendanceType.CHECKPOINT, "CP-CLINIC", at = 950L, sync = SyncState.PENDING, id = "b"),
            ),
        )

        assertTrue(state.stops.single().isDone)
    }

    /** The QR carries the code in whatever case it was printed. Matching must not care. */
    @Test
    fun `post codes match regardless of case`() = runTest {
        val state = state(
            posts = listOf(post(1, "CP-CLINIC")),
            records = listOf(
                record(AttendanceType.CHECKPOINT, "cp-clinic", at = 700L),
                record(AttendanceType.CHECKPOINT, "CP-Clinic", at = 800L, id = "b"),
            ),
        )

        assertEquals(2, state.stops.single().visits)
        assertTrue(state.stops.single().isDone)
    }

    private fun TestScope.state(
        posts: List<Checkpoint>,
        records: List<AttendanceRecord>,
    ): RoundUiState {
        val viewModel = RoundViewModel(
            checkpoints = FakeCheckpoints(posts),
            attendance = FakeAttendance(records),
            settings = FakeSettings,
            savedStateHandle = SavedStateHandle(mapOf("date" to "2026-07-13")),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel.uiState.value
    }

    private fun post(id: Long, code: String) = Checkpoint(
        id = id,
        code = code,
        name = code,
        isActive = true,
        latitude = null,
        longitude = null,
    )

    private fun record(
        type: AttendanceType,
        code: String,
        at: Long,
        sync: SyncState = SyncState.SYNCED,
        id: String = "a",
    ) = AttendanceRecord(
        id = "$type-$code-$id",
        checkpointCode = code,
        type = type,
        capturedAt = at,
        selfiePath = "",
        latitude = null,
        longitude = null,
        accuracyMetres = null,
        syncState = sync,
        lastError = null,
    )

    private object FakeSettings : SettingsRepository {
        override fun observe(): Flow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun current(): AppSettings = AppSettings()
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    private class FakeCheckpoints(private val posts: List<Checkpoint>) : CheckpointRepository {
        override suspend fun resolve(code: String): CheckpointResolution =
            CheckpointResolution.Unknown(code)
        override suspend fun byId(id: Long): Checkpoint? = posts.firstOrNull { it.id == id }
        override fun observeActive(): Flow<List<Checkpoint>> = MutableStateFlow(posts)
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    private class FakeAttendance(
        private val records: List<AttendanceRecord>,
    ) : AttendanceRepository {
        override fun observeUnsyncedCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeOtherAccountUnsyncedCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeHistory(limit: Int): Flow<List<AttendanceRecord>> =
            MutableStateFlow(records)
        override fun observeRecord(id: String): Flow<AttendanceRecord?> = MutableStateFlow(null)
        override suspend fun retry(id: String) = Unit
        override fun observeStuckCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeRejectedCount(): Flow<Int> = MutableStateFlow(0)
        override suspend fun discardRejected(): Int = 0
        override suspend fun syncNow(): SyncOutcome = SyncOutcome()
        override fun observeInRange(
            fromMillis: Long,
            toMillis: Long,
        ): Flow<List<AttendanceRecord>> = MutableStateFlow(records)
        override suspend fun checkpointVisitsToday(): Map<Long, Int> = emptyMap()
        override suspend fun lastVisitedCheckpointToday(): Long? = null
        override suspend fun hasTimedOutToday(): Boolean = false
        override suspend fun submit(id: String, draft: AttendanceDraft) = Unit
        override suspend fun refreshHistory(): ApiResult<Int> = ApiResult.Success(0)
    }
}
