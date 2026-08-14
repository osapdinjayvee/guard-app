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
     * A post where shifts start and end is not a stop on the round.
     *
     * The guard's presence at the guard house is already recorded — twice, by the Time In and the
     * Time Out. Listing it again as somewhere to visit asks them to walk to the door they clocked
     * on at, and puts the shift's bookends on the round list under another name.
     */
    @Test
    fun `a shift post is not a stop on the round`() = runTest {
        val state = state(
            posts = listOf(
                post(1, "GUARD_HOUSE", allowsTimeInOut = true),
                post(2, "CP-LIBRARY"),
            ),
            records = listOf(record(AttendanceType.TIME_IN, "GUARD_HOUSE", at = 500L)),
        )

        assertEquals(listOf("CP-LIBRARY"), state.stops.map { it.checkpoint.code })
        assertEquals(1, state.total)
        // The Time In still shows as the shift's opening; it is simply not a patrol stop.
        assertEquals(500L, state.timedInAt)
    }

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
     * The Time In counts as one visit to the post it was taken at — and only one.
     *
     * This reverses the earlier rule, which counted checkpoint scans alone. The concern then was
     * that crediting the Time In would tell a guard they had already patrolled the post they
     * clocked on at, and they would skip it. With two visits required that does not follow: the
     * post shows 1 of 2 and still appears in the list of what is left. What the old rule actually
     * produced was the opposite failure — the post where the shift opened was permanently one
     * visit behind every other, and since the same post cannot be scanned twice in a row, closing
     * the shift meant a detour at the end of an eight-hour round.
     *
     * The server counts it the same way. If these ever diverge the app offers a Time Out that is
     * then refused, which is the worst place to find out.
     *
     * Note the consequence where only *one* visit is required: there, a Time In alone completes
     * the post. That is the same principle carried through — the guard was there, with a selfie to
     * prove it — but it is a real change for any campus configured that way.
     */
    @Test
    fun `timing in at a post counts as one visit to it`() = runTest {
        val state = state(
            posts = listOf(post(1, "CP-MAIN-GATE")),
            records = listOf(record(AttendanceType.TIME_IN, "CP-MAIN-GATE", at = 500L)),
        )

        assertEquals(1, state.stops.single().visits)
        assertEquals(500L, state.stops.single().lastVisitedAt)

        // One visit of the two: still owed, still listed, not quietly ticked off.
        assertFalse(state.stops.single().isDone)
        assertEquals(0, state.done)
        assertEquals(500L, state.timedInAt)
    }

    /** The Time Out cannot count toward the round it is asking permission for. */
    @Test
    fun `timing out at a post does not scan that post`() = runTest {
        val state = state(
            posts = listOf(post(1, "CP-MAIN-GATE")),
            records = listOf(record(AttendanceType.TIME_OUT, "CP-MAIN-GATE", at = 900L)),
        )

        assertEquals(0, state.stops.single().visits)
        assertNull(state.stops.single().lastVisitedAt)
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

    /**
     * A stop on the round, which means a patrol post.
     *
     * Defaulted to `allowsTimeInOut = false` because that is what a round is made of. A post where
     * shifts start and end is not a stop: the guard's presence there is already recorded by the
     * Time In and the Time Out.
     */
    private fun post(id: Long, code: String, allowsTimeInOut: Boolean = false) = Checkpoint(
        id = id,
        code = code,
        name = code,
        isActive = true,
        latitude = null,
        longitude = null,
        allowsTimeInOut = allowsTimeInOut,
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
