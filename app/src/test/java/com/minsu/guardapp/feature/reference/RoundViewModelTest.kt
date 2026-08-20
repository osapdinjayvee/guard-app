package com.minsu.guardapp.feature.reference

import androidx.lifecycle.SavedStateHandle
import com.minsu.guardapp.domain.model.ShiftWindow
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.model.SyncOutcome
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.model.SyncState
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.ScheduleRepository
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class RoundViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private companion object {
        /** The date most cases run on. Nothing is rostered against it, so it falls back to the day. */
        const val DATE = "2026-07-13"

        /** The rostering from the field report: a night shift, then the next afternoon. */
        val nightThenAfternoon = listOf(
            duty("2026-08-19", "23:00", "06:00"),
            duty("2026-08-20", "15:00", "23:00"),
        )

        fun duty(date: String, startsAt: String, endsAt: String) = DutyAssignment(
            date = date,
            dutyType = DutyType.ROVING,
            dutyName = "Roving Guard",
            startsAt = startsAt,
            endsAt = endsAt,
            totalHours = 8f,
        )

        fun midnight(date: String): Long = Calendar.getInstance().apply {
            time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun hours(count: Int): Long = count * 60L * 60 * 1000
    }

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
        assertEquals(midnight(DATE) + 500L, state.timedInAt)
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
        assertEquals(midnight(DATE) + 2_000L, gate.lastVisitedAt)

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
        assertEquals(midnight(DATE) + 500L, state.stops.single().lastVisitedAt)

        // One visit of the two: still owed, still listed, not quietly ticked off.
        assertFalse(state.stops.single().isDone)
        assertEquals(0, state.done)
        assertEquals(midnight(DATE) + 500L, state.timedInAt)
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

    /**
     * Reported from the field.
     *
     * Rostered 23:00–06:00 on the 19th and 15:00–23:00 on the 20th, the guard opened the 20th and
     * found a Time Out already against it at 6am — nine hours before that shift even starts. It
     * was the previous night's, claimed by the 20th because the round asked about the calendar day
     * rather than about the shift.
     */
    @Test
    fun `the previous night's time out does not appear on the next day's round`() = runTest {
        val state = state(
            posts = listOf(post(1, "CP-LIBRARY")),
            records = listOf(
                record(AttendanceType.TIME_OUT, "GUARD_HOUSE", at = hours(6), date = "2026-08-20"),
                record(AttendanceType.TIME_IN, "GUARD_HOUSE", at = hours(15), id = "b", date = "2026-08-20"),
            ),
            duties = nightThenAfternoon,
            date = "2026-08-20",
        )

        assertNull(state.timedOutAt)
        assertEquals(midnight("2026-08-20") + hours(15), state.timedInAt)
    }

    /** The other half of it: the night shift keeps the Time Out that closes it. */
    @Test
    fun `a night shift's round holds the time out that closes it`() = runTest {
        val state = state(
            posts = listOf(post(1, "CP-LIBRARY")),
            records = listOf(
                record(AttendanceType.TIME_OUT, "GUARD_HOUSE", at = hours(6), date = "2026-08-20"),
            ),
            duties = nightThenAfternoon,
            date = "2026-08-19",
        )

        assertEquals(midnight("2026-08-20") + hours(6), state.timedOutAt)
    }

    /**
     * A Time Out four minutes past the scheduled end still closes that shift.
     *
     * Bounding the round by the bare shift hours would leave every ordinary same-day shift looking
     * as though it had never been closed — the same complaint as the night-shift one, reached from
     * the other direction.
     */
    @Test
    fun `a late time out still closes a same-day shift`() = runTest {
        val state = state(
            posts = listOf(post(1, "CP-LIBRARY")),
            records = listOf(
                record(
                    AttendanceType.TIME_OUT,
                    "GUARD_HOUSE",
                    at = hours(23) + 4 * 60 * 1000L,
                    date = "2026-08-20",
                ),
            ),
            duties = nightThenAfternoon,
            date = "2026-08-20",
        )

        assertEquals(midnight("2026-08-20") + hours(23) + 4 * 60 * 1000L, state.timedOutAt)
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
        duties: List<DutyAssignment> = emptyList(),
        date: String = DATE,
    ): RoundUiState {
        val viewModel = RoundViewModel(
            checkpoints = FakeCheckpoints(posts),
            attendance = FakeAttendance(records),
            settings = FakeSettings,
            schedule = FakeSchedule(duties),
            savedStateHandle = SavedStateHandle(mapOf("date" to date)),
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

    /**
     * A record on the round's own date.
     *
     * [at] is an offset from that date's midnight rather than an absolute instant, because the
     * round is now bounded by a real window and a bare `500L` is a moment in 1970 — outside every
     * window there is. The cases below care about the order records fall in, not the hour.
     */
    private fun record(
        type: AttendanceType,
        code: String,
        at: Long,
        sync: SyncState = SyncState.SYNCED,
        id: String = "a",
        date: String = DATE,
    ) = AttendanceRecord(
        id = "$type-$code-$id",
        checkpointCode = code,
        type = type,
        capturedAt = midnight(date) + at,
        selfiePath = "",
        latitude = null,
        longitude = null,
        accuracyMetres = null,
        syncState = sync,
        lastError = null,
    )

    private class FakeSchedule(private val duties: List<DutyAssignment>) : ScheduleRepository {
        override fun observeCurrentDuty(): Flow<DutyAssignment?> = MutableStateFlow(duties.firstOrNull())
        override fun observeAll(): Flow<List<DutyAssignment>> = MutableStateFlow(duties)
        override suspend fun currentDuty(): DutyAssignment? = duties.firstOrNull()
        override val isLinked: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun postTimedInAt(window: ShiftWindow): Long? = null
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

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
        /*
         * Honours the range, which this fake used not to do.
         *
         * Returning everything regardless of the bounds made every test here pass no matter what
         * window the ViewModel asked for — so the round could go on counting a whole calendar day
         * while the suite reported it was fine. The bug a guard found on their own phone, a night
         * shift's 6am Time Out appearing on the next day's round, was invisible from in here.
         */
        override fun observeInRange(
            fromMillis: Long,
            toMillis: Long,
        ): Flow<List<AttendanceRecord>> =
            MutableStateFlow(records.filter { it.capturedAt in fromMillis..toMillis })
        override suspend fun checkpointVisitsIn(window: ShiftWindow): Map<Long, Int> = emptyMap()
        override suspend fun lastVisitedCheckpointIn(window: ShiftWindow): Long? = null
        override suspend fun hasTimedOutIn(window: ShiftWindow): Boolean = false
        override suspend fun submit(id: String, draft: AttendanceDraft) = Unit
        override suspend fun refreshHistory(): ApiResult<Int> = ApiResult.Success(0)
    }
}
