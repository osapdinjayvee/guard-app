package com.minsu.guardapp.feature.scan

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
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
import kotlinx.coroutines.flow.first
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
class ScanViewModelTest {

    private class FakeCheckpoints(
        private val resolutions: Map<String, CheckpointResolution> = emptyMap(),
        /** The campus's active posts. The round rule measures against these, so they must be real. */
        private val active: List<Checkpoint> = emptyList(),
    ) : CheckpointRepository {
        var resolveCalls = 0
        override suspend fun resolve(code: String): CheckpointResolution {
            resolveCalls++
            return resolutions[code] ?: CheckpointResolution.Unknown(code)
        }
        override suspend fun byId(id: Long): Checkpoint? = active.firstOrNull { it.id == id }
        override fun observeActive(): Flow<List<Checkpoint>> = MutableStateFlow(active)
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    /**
     * A roster. Defaults to a roving guard with no Time In yet, which is the permissive case: every
     * type allowed, every checkpoint allowed. Tests that care about the stationed rules say so.
     */
    private class FakeRoster(
        private val duty: DutyAssignment? = DutyAssignment(
            date = "2026-07-12",
            dutyType = DutyType.ROVING,
            dutyName = "Roving Guard",
            startsAt = null,
            endsAt = null,
            totalHours = 8f,
        ),
        private val linked: Boolean = true,
        private val timedInAt: Long? = null,
    ) : ScheduleRepository {
        override fun observeToday(): Flow<DutyAssignment?> = MutableStateFlow(duty)
        override fun observeAll(): Flow<List<DutyAssignment>> = MutableStateFlow(listOfNotNull(duty))
        override suspend fun today(): DutyAssignment? = duty
        override val isLinked: Flow<Boolean> = MutableStateFlow(linked)
        override suspend fun postTimedInAtToday(): Long? = timedInAt
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    /**
     * The default roster: a roving guard who has already timed in.
     *
     * Timed in on purpose. A patrol cannot start before the shift does, so a guard with no Time In is
     * offered no checkpoint visit — which is a state worth testing, but not the one most of these
     * tests are about. The tests that care say so.
     */
    private fun roster() = FakeRoster(timedInAt = 1L)

    /** What the default (roving) roster permits *before* timing in. */
    private val ROVING_TYPES = listOf(
        AttendanceType.TIME_IN,
        AttendanceType.CHECKPOINT,
        AttendanceType.TIME_OUT,
    )

    /** What a roving guard is offered once they have timed in: no second Time In. */
    private val POST_TIMEIN_TYPES = listOf(
        AttendanceType.CHECKPOINT,
        AttendanceType.TIME_OUT,
    )

    private val gateA = Checkpoint(1, "GATE-A", "Main Gate", isActive = true, latitude = null, longitude = null)
    private val clinic = Checkpoint(2, "CLINIC", "Clinic", isActive = true, latitude = null, longitude = null)
    private val roofOld = Checkpoint(4, "ROOF-OLD", "Rooftop", isActive = false, latitude = null, longitude = null)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `starts scanning`() = runTest {
        assertEquals(ScanState.Scanning, scanner(schedule = roster(), checkpoints = FakeCheckpoints()).state.value)
    }

    @Test
    fun `an active checkpoint moves straight to type selection`() = runTest {
        val vm = scanner(schedule = roster(), checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))))

        vm.onCodeScanned("GATE-A")

        assertEquals(ScanState.ChoosingType(gateA, POST_TIMEIN_TYPES), vm.state.value)
    }

    /** A retired checkpoint must not read as "unrecognised code". */
    @Test
    fun `a disabled checkpoint is reported as retired, not unknown`() = runTest {
        val vm = scanner(schedule = roster(), checkpoints = FakeCheckpoints(mapOf("ROOF-OLD" to CheckpointResolution.Disabled(roofOld))))

        vm.onCodeScanned("ROOF-OLD")

        assertTrue(vm.state.value is ScanState.Disabled)
    }

    @Test
    fun `an unknown code is reported as unknown`() = runTest {
        val vm = scanner(schedule = roster(), checkpoints = FakeCheckpoints())

        vm.onCodeScanned("NOT-A-CODE")

        assertEquals(ScanState.Unknown("NOT-A-CODE"), vm.state.value)
    }

    /**
     * A QR code sits in frame for many frames. Resolving on every one of them would hammer the
     * DAO and could flip the screen between states.
     */
    @Test
    fun `further scans are ignored until the guard dismisses the result`() = runTest {
        val repo = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA)))
        val vm = scanner(checkpoints = repo, schedule = roster())

        vm.onCodeScanned("GATE-A")
        vm.onCodeScanned("GATE-A")
        vm.onCodeScanned("GATE-B")

        assertEquals(1, repo.resolveCalls)
        assertEquals(ScanState.ChoosingType(gateA, POST_TIMEIN_TYPES), vm.state.value)
    }

    @Test
    fun `scan again re-arms the scanner`() = runTest {
        val repo = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA)))
        val vm = scanner(checkpoints = repo, schedule = roster())
        vm.onCodeScanned("GATE-A")

        vm.scanAgain()

        assertEquals(ScanState.Scanning, vm.state.value)
        vm.onCodeScanned("GATE-A")
        assertEquals(2, repo.resolveCalls)
    }

    @Test
    fun `choosing a type carries the checkpoint forward`() = runTest {
        val vm = scanner(schedule = roster(), checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))))
        vm.onCodeScanned("GATE-A")

        vm.onTypeChosen(AttendanceType.TIME_OUT)

        assertEquals(ScanState.ReadyToCapture(gateA, AttendanceType.TIME_OUT), vm.state.value)
    }

    /** A type without a resolved checkpoint is meaningless and must not be reachable. */
    @Test
    fun `choosing a type while still scanning is ignored`() = runTest {
        val vm = scanner(schedule = roster(), checkpoints = FakeCheckpoints())

        vm.onTypeChosen(AttendanceType.TIME_IN)

        assertEquals(ScanState.Scanning, vm.state.value)
    }

    @Test
    fun `choosing a type on a disabled checkpoint is ignored`() = runTest {
        val vm = scanner(schedule = roster(), checkpoints = FakeCheckpoints(mapOf("ROOF-OLD" to CheckpointResolution.Disabled(roofOld))))
        vm.onCodeScanned("ROOF-OLD")

        vm.onTypeChosen(AttendanceType.TIME_IN)

        assertTrue("must not fall through to capture", vm.state.value is ScanState.Disabled)
    }

    // --- The duty roster ---

    private fun stationed(timedInAt: Long? = null) = FakeRoster(
        duty = DutyAssignment(
            date = "2026-07-12",
            dutyType = DutyType.STATIONED,
            dutyName = "Stationed Guard",
            startsAt = null,
            endsAt = null,
            totalHours = 8f,
        ),
        timedInAt = timedInAt,
    )

    private class NamingCheckpoints(
        private val resolutions: Map<String, CheckpointResolution>,
        private val byId: Map<Long, Checkpoint> = emptyMap(),
    ) : CheckpointRepository {
        override suspend fun resolve(code: String): CheckpointResolution =
            resolutions[code] ?: CheckpointResolution.Unknown(code)
        override suspend fun byId(id: Long): Checkpoint? = byId[id]
        override fun observeActive(): Flow<List<Checkpoint>> = MutableStateFlow(emptyList())
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    /**
     * A stationed guard is at one post, and there is nothing to visit. Offering them a Checkpoint
     * button would be offering a choice the server is certain to reject.
     */
    @Test
    fun `a stationed guard is offered Time In and Time Out, and never a patrol visit`() = runTest {
        val vm = scanner(
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
            schedule = stationed(),
        )

        vm.onCodeScanned("GATE-A")

        val state = vm.state.value as ScanState.ChoosingType
        assertEquals(listOf(AttendanceType.TIME_IN, AttendanceType.TIME_OUT), state.allowedTypes)
        assertTrue(AttendanceType.CHECKPOINT !in state.allowedTypes)
    }

    /**
     * The post is not assigned by anyone — it is wherever they scan in. Before the first Time In,
     * every checkpoint is fair game, and the one they pick becomes the one they are held to.
     */
    @Test
    fun `a stationed guard who has not timed in may scan any checkpoint`() = runTest {
        val vm = scanner(
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
            schedule = stationed(timedInAt = null),
        )

        vm.onCodeScanned("GATE-A")

        assertTrue("${vm.state.value}", vm.state.value is ScanState.ChoosingType)
    }

    /**
     * The rule, at the wrong door: a stationed shift ends where it began.
     *
     * Told here, while the guard can still walk back to the right checkpoint — rather than by a
     * rejection that lands after the shift is over, when the capture is already wasted.
     */
    @Test
    fun `a stationed guard is stopped at a checkpoint they did not time in at`() = runTest {
        val library = Checkpoint(9, "CP-LIBRARY", "Library", isActive = true, latitude = null, longitude = null)
        val vm = scanner(
            checkpoints = NamingCheckpoints(
                resolutions = mapOf("CP-LIBRARY" to CheckpointResolution.Resolved(library)),
                byId = mapOf(1L to gateA),
            ),
            schedule = stationed(timedInAt = gateA.id),
        )

        vm.onCodeScanned("CP-LIBRARY")

        val state = vm.state.value as ScanState.WrongPost
        assertEquals(library, state.scanned)
        assertEquals("names the post they actually timed in at", "GATE-A", state.timedInAt)
    }

    /** Scanning the post they timed in at is exactly what a stationed guard is supposed to do. */
    @Test
    fun `a stationed guard may scan the post they timed in at`() = runTest {
        val vm = scanner(
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
            schedule = stationed(timedInAt = gateA.id),
        )

        vm.onCodeScanned("GATE-A")

        assertTrue("${vm.state.value}", vm.state.value is ScanState.ChoosingType)
    }

    /** A roving guard has no post. Timing in at one checkpoint does not bind them to it. */
    @Test
    fun `a roving guard may scan a checkpoint other than the one they timed in at`() = runTest {
        val library = Checkpoint(9, "CP-LIBRARY", "Library", isActive = true, latitude = null, longitude = null)
        val vm = scanner(
            checkpoints = FakeCheckpoints(mapOf("CP-LIBRARY" to CheckpointResolution.Resolved(library))),
            schedule = FakeRoster(timedInAt = gateA.id),
        )

        vm.onCodeScanned("CP-LIBRARY")

        val state = vm.state.value as ScanState.ChoosingType
        assertEquals(POST_TIMEIN_TYPES, state.allowedTypes)
    }

    /** A rest day is not an error, and must not be worded as one. */
    @Test
    fun `a guard with no shift today is told so, not shown a scanner error`() = runTest {
        val vm = scanner(
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
            schedule = FakeRoster(duty = null),
        )

        vm.onCodeScanned("GATE-A")

        assertEquals(ScanState.NotScheduledToday, vm.state.value)
    }

    /**
     * "Nobody linked your account" and "you have no shift" look identical to a guard and mean
     * entirely different things — one is a rest day, the other is an admin error that will keep
     * them from working until somebody fixes it.
     */
    @Test
    fun `an unlinked account is named as an admin problem, not a rest day`() = runTest {
        val vm = scanner(
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
            schedule = FakeRoster(linked = false),
        )

        vm.onCodeScanned("GATE-A")

        assertEquals(ScanState.NotOnRoster, vm.state.value)
    }

    /**
     * The ViewModel under test, with the time-based collaborators supplied.
     *
     * `visits` defaults to the minimum, so the tests that are not about the round see the roster's
     * full set of types. The ones that *are* about it say so.
     */
    private fun scanner(
        schedule: ScheduleRepository,
        checkpoints: CheckpointRepository,
        visits: Map<Long, Int> = emptyMap(),
        lastVisited: Long? = null,
        timedOut: Boolean = false,
        settings: AppSettings = AppSettings(),
        nowMillis: Long = NOON,
    ) = ScanViewModel(
        checkpoints = checkpoints,
        schedule = schedule,
        attendance = FakeAttendance(visits, lastVisited, timedOut),
        settings = FakeSettings(settings),
        clock = Clock { nowMillis },
    )

    private class FakeAttendance(
        private val visits: Map<Long, Int>,
        private val lastVisited: Long? = null,
        private val timedOut: Boolean = false,
    ) : AttendanceRepository {
        override fun observeUnsyncedCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeOtherAccountUnsyncedCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeHistory(limit: Int): Flow<List<AttendanceRecord>> = MutableStateFlow(emptyList())
        override fun observeRecord(id: String): Flow<AttendanceRecord?> = MutableStateFlow(null)
        override suspend fun retry(id: String) = Unit
        override fun observeStuckCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeRejectedCount(): Flow<Int> = MutableStateFlow(0)
        override suspend fun discardRejected(): Int = 0
        override suspend fun syncNow(): Int = 0
        override fun observeInRange(fromMillis: Long, toMillis: Long): Flow<List<AttendanceRecord>> =
            MutableStateFlow(emptyList())
        override suspend fun checkpointVisitsToday(): Map<Long, Int> = visits
        override suspend fun lastVisitedCheckpointToday(): Long? = lastVisited
        override suspend fun hasTimedOutToday(): Boolean = timedOut
        override suspend fun submit(id: String, draft: AttendanceDraft) = Unit
        override suspend fun refreshHistory(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    private class FakeSettings(private val settings: AppSettings) : SettingsRepository {
        override fun observe(): Flow<AppSettings> = MutableStateFlow(settings)
        override suspend fun current(): AppSettings = settings
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    private companion object {
        /** 2026-07-12, midday, device timezone. The roster fixtures are dated the same day. */
        val NOON: Long = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .parse("2026-07-12 12:00")!!.time
    }

    // ---- The two rules that depend on when, not where -------------------------------------------

    /**
     * The round is every post, twice — not two scans anywhere.
     *
     * A post visited once is not done, and a post never reached is not excused by another post being
     * finished. Time Out is *removed* rather than shown and refused: a button whose only purpose is
     * to reject you is a trap. The notice names what is still owed, because a guard shown fewer
     * buttons and no reason has been told nothing.
     */
    @Test
    fun `a roving guard cannot time out until every post has its visits`() = runTest {
        val vm = scanner(
            schedule = roster(),
            checkpoints = FakeCheckpoints(
                resolutions = mapOf("GATE-A" to CheckpointResolution.Resolved(gateA)),
                active = listOf(gateA, clinic),
            ),
            // Gate A is done twice over; the Clinic has been reached once. The round is not walked.
            visits = mapOf(gateA.id to 2, clinic.id to 1),
        )

        vm.onCodeScanned("GATE-A")

        val state = vm.state.value as ScanState.ChoosingType
        assertFalse(AttendanceType.TIME_OUT in state.allowedTypes)
        assertTrue(AttendanceType.CHECKPOINT in state.allowedTypes)
        assertTrue(state.notice!!.contains("CLINIC"))
        assertFalse(state.notice!!.contains("GATE-A"))
    }

    @Test
    fun `the round unlocks time out once every post has its visits`() = runTest {
        val vm = scanner(
            schedule = roster(),
            checkpoints = FakeCheckpoints(
                resolutions = mapOf("GATE-A" to CheckpointResolution.Resolved(gateA)),
                active = listOf(gateA, clinic),
            ),
            visits = mapOf(gateA.id to 2, clinic.id to 2),
        )

        vm.onCodeScanned("GATE-A")

        val state = vm.state.value as ScanState.ChoosingType
        assertEquals(POST_TIMEIN_TYPES, state.allowedTypes)
        assertNull(state.notice)
    }

    /** The round rule is for roving guards. A stationed guard has one post and no round to walk. */
    @Test
    fun `a stationed guard can time out without any checkpoint visits`() = runTest {
        val vm = scanner(
            schedule = stationed(),
            checkpoints = FakeCheckpoints(
                resolutions = mapOf("GATE-A" to CheckpointResolution.Resolved(gateA)),
                active = listOf(gateA, clinic),
            ),
            visits = emptyMap(),
        )

        vm.onCodeScanned("GATE-A")

        val state = vm.state.value as ScanState.ChoosingType
        assertTrue(AttendanceType.TIME_OUT in state.allowedTypes)
    }

    /** Turning up an hour early and timing in does not make the shift an hour longer. */
    @Test
    fun `time in is not offered before the shift opens`() = runTest {
        val vm = scanner(
            schedule = rosterStartingAt("14:00:00"),
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
            nowMillis = at("2026-07-12 13:30"), // 30 minutes out; the window opens at 13:45
        )

        vm.onCodeScanned("GATE-A")

        val state = vm.state.value as ScanState.ChoosingType
        assertFalse(AttendanceType.TIME_IN in state.allowedTypes)
        assertTrue(state.notice!!.contains("1:45 PM"))
    }

    @Test
    fun `time in opens fifteen minutes before the shift`() = runTest {
        val vm = scanner(
            schedule = rosterStartingAt("14:00:00"),
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
            nowMillis = at("2026-07-12 13:45"),
        )

        vm.onCodeScanned("GATE-A")

        assertTrue(AttendanceType.TIME_IN in (vm.state.value as ScanState.ChoosingType).allowedTypes)
    }

    /**
     * Lateness is not capped, and must not be. The late timestamp is itself the evidence — refusing
     * it would leave the shift with no record at all, which serves neither the guard nor the office.
     */
    @Test
    fun `a late guard can still time in`() = runTest {
        val vm = scanner(
            schedule = rosterStartingAt("14:00:00"),
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
            nowMillis = at("2026-07-12 16:20"), // two hours late
        )

        vm.onCodeScanned("GATE-A")

        val state = vm.state.value as ScanState.ChoosingType
        assertTrue(AttendanceType.TIME_IN in state.allowedTypes)
        // They have not timed in yet — that is the whole point of the test — so the patrol notice is
        // expected. What must be absent is any complaint about being *early*.
        assertFalse(state.notice!!.contains("Time In opens"))
    }

    private fun rosterStartingAt(startsAt: String) = FakeRoster(
        duty = DutyAssignment(
            date = "2026-07-12",
            dutyType = DutyType.ROVING,
            dutyName = "Roving Guard",
            startsAt = startsAt,
            endsAt = null,
            totalHours = 8f,
        ),
    )

    private fun at(wallClock: String): Long =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(wallClock)!!.time

    /**
     * A patrol-only post cannot open or close a shift.
     *
     * A perimeter marker is somewhere a roving guard passes on a round; nobody clocks on at one. The
     * two shift types are removed, the visit stays, and the notice says which post this is — a guard
     * shown only "Checkpoint" and no reason would think the app was broken.
     */
    @Test
    fun `a patrol-only checkpoint offers a visit and nothing else`() = runTest {
        val marker = Checkpoint(
            id = 7,
            code = "FENCE-3",
            name = "Perimeter marker",
            isActive = true,
            latitude = null,
            longitude = null,
            allowsTimeInOut = false,
        )
        val vm = scanner(
            schedule = roster(),
            checkpoints = FakeCheckpoints(mapOf("FENCE-3" to CheckpointResolution.Resolved(marker))),
        )

        vm.onCodeScanned("FENCE-3")

        val state = vm.state.value as ScanState.ChoosingType
        assertEquals(listOf(AttendanceType.CHECKPOINT), state.allowedTypes)
        assertTrue(state.notice!!.contains("FENCE-3"))
        assertTrue(state.notice!!.contains("patrol checkpoint"))
    }

    /**
     * A patrol is movement.
     *
     * A guard who scans the same door twice in succession has not gone anywhere, and the round must
     * not be satisfiable by standing at one post. The visit is removed; the notice says to walk on.
     */
    @Test
    fun `the same post cannot be scanned twice in a row`() = runTest {
        val vm = scanner(
            schedule = roster(),
            checkpoints = FakeCheckpoints(
                resolutions = mapOf("GATE-A" to CheckpointResolution.Resolved(gateA)),
                active = listOf(gateA, clinic),
            ),
            visits = mapOf(gateA.id to 1),
            lastVisited = gateA.id,
        )

        vm.onCodeScanned("GATE-A")

        val state = vm.state.value as ScanState.ChoosingType
        assertFalse(AttendanceType.CHECKPOINT in state.allowedTypes)
        assertTrue(state.notice!!.contains("just visited GATE-A"))
    }

    /** Another post in between is exactly what the rule asks for. It must then be scannable again. */
    @Test
    fun `a post can be scanned again once another has been visited`() = runTest {
        val vm = scanner(
            schedule = roster(),
            checkpoints = FakeCheckpoints(
                resolutions = mapOf("GATE-A" to CheckpointResolution.Resolved(gateA)),
                active = listOf(gateA, clinic),
            ),
            visits = mapOf(gateA.id to 1, clinic.id to 1),
            lastVisited = clinic.id,
        )

        vm.onCodeScanned("GATE-A")

        val state = vm.state.value as ScanState.ChoosingType
        assertTrue(AttendanceType.CHECKPOINT in state.allowedTypes)
    }

    /**
     * A patrol starts when the shift does.
     *
     * A checkpoint visit recorded before any Time In would be evidence of a round walked by a guard
     * who had not clocked on — and it would count toward a round they were never on duty to walk.
     */
    @Test
    fun `a checkpoint visit is not offered before the guard has timed in`() = runTest {
        val vm = scanner(
            schedule = FakeRoster(), // no Time In yet
            checkpoints = FakeCheckpoints(
                resolutions = mapOf("GATE-A" to CheckpointResolution.Resolved(gateA)),
                active = listOf(gateA, clinic),
            ),
        )

        vm.onCodeScanned("GATE-A")

        val state = vm.state.value as ScanState.ChoosingType
        assertFalse(AttendanceType.CHECKPOINT in state.allowedTypes)
        assertTrue(AttendanceType.TIME_IN in state.allowedTypes)
        assertTrue(state.notice!!.contains("Time In first"))
    }

    @Test
    fun `a checkpoint visit is offered once the guard has timed in`() = runTest {
        val vm = scanner(
            schedule = FakeRoster(timedInAt = gateA.id),
            checkpoints = FakeCheckpoints(
                resolutions = mapOf("CLINIC" to CheckpointResolution.Resolved(clinic)),
                active = listOf(gateA, clinic),
            ),
        )

        vm.onCodeScanned("CLINIC")

        val state = vm.state.value as ScanState.ChoosingType
        assertTrue(AttendanceType.CHECKPOINT in state.allowedTypes)
    }

    /**
     * One Time In per shift. A guard who has already clocked on must not be offered Time In again —
     * a second Time In stacks a new shift on the open one. Only Time Out (and, for a rover, a
     * checkpoint visit) remain.
     */
    @Test
    fun `a guard who has already timed in is not offered Time In again`() = runTest {
        val vm = scanner(
            schedule = FakeRoster(timedInAt = gateA.id),
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
        )

        vm.onCodeScanned("GATE-A")

        val state = vm.state.value as ScanState.ChoosingType
        assertFalse("no second Time In once clocked on", AttendanceType.TIME_IN in state.allowedTypes)
        assertTrue(AttendanceType.TIME_OUT in state.allowedTypes)
    }

    /**
     * After Time Out the shift is closed. Scanning says the shift is complete rather than offering
     * another Time Out — the guard clocks in again only with their next shift.
     */
    @Test
    fun `a guard who has timed out is told the shift is complete, not offered another time out`() = runTest {
        val vm = scanner(
            schedule = roster(),
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
            timedOut = true,
        )

        vm.onCodeScanned("GATE-A")

        assertTrue("${vm.state.value}", vm.state.value is ScanState.ShiftComplete)
    }
}
