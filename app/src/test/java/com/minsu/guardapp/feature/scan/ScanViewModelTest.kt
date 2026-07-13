package com.minsu.guardapp.feature.scan

import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

    private class FakeCheckpoints(
        private val resolutions: Map<String, CheckpointResolution> = emptyMap(),
    ) : CheckpointRepository {
        var resolveCalls = 0
        override suspend fun resolve(code: String): CheckpointResolution {
            resolveCalls++
            return resolutions[code] ?: CheckpointResolution.Unknown(code)
        }
        override suspend fun byId(id: Long): Checkpoint? = null
        override fun observeActive(): Flow<List<Checkpoint>> = MutableStateFlow(emptyList())
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

    private fun roster() = FakeRoster()

    /** What the default (roving) roster permits. */
    private val ROVING_TYPES = listOf(
        AttendanceType.TIME_IN,
        AttendanceType.CHECKPOINT,
        AttendanceType.TIME_OUT,
    )

    private val gateA = Checkpoint(1, "GATE-A", "Main Gate", isActive = true, latitude = null, longitude = null)
    private val roofOld = Checkpoint(4, "ROOF-OLD", "Rooftop", isActive = false, latitude = null, longitude = null)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `starts scanning`() = runTest {
        assertEquals(ScanState.Scanning, ScanViewModel(schedule = roster(), checkpoints = FakeCheckpoints()).state.value)
    }

    @Test
    fun `an active checkpoint moves straight to type selection`() = runTest {
        val vm = ScanViewModel(schedule = roster(), checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))))

        vm.onCodeScanned("GATE-A")

        assertEquals(ScanState.ChoosingType(gateA, ROVING_TYPES), vm.state.value)
    }

    /** A retired checkpoint must not read as "unrecognised code". */
    @Test
    fun `a disabled checkpoint is reported as retired, not unknown`() = runTest {
        val vm = ScanViewModel(schedule = roster(), checkpoints = FakeCheckpoints(mapOf("ROOF-OLD" to CheckpointResolution.Disabled(roofOld))))

        vm.onCodeScanned("ROOF-OLD")

        assertTrue(vm.state.value is ScanState.Disabled)
    }

    @Test
    fun `an unknown code is reported as unknown`() = runTest {
        val vm = ScanViewModel(schedule = roster(), checkpoints = FakeCheckpoints())

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
        val vm = ScanViewModel(checkpoints = repo, schedule = roster())

        vm.onCodeScanned("GATE-A")
        vm.onCodeScanned("GATE-A")
        vm.onCodeScanned("GATE-B")

        assertEquals(1, repo.resolveCalls)
        assertEquals(ScanState.ChoosingType(gateA, ROVING_TYPES), vm.state.value)
    }

    @Test
    fun `scan again re-arms the scanner`() = runTest {
        val repo = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA)))
        val vm = ScanViewModel(checkpoints = repo, schedule = roster())
        vm.onCodeScanned("GATE-A")

        vm.scanAgain()

        assertEquals(ScanState.Scanning, vm.state.value)
        vm.onCodeScanned("GATE-A")
        assertEquals(2, repo.resolveCalls)
    }

    @Test
    fun `choosing a type carries the checkpoint forward`() = runTest {
        val vm = ScanViewModel(schedule = roster(), checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))))
        vm.onCodeScanned("GATE-A")

        vm.onTypeChosen(AttendanceType.TIME_OUT)

        assertEquals(ScanState.ReadyToCapture(gateA, AttendanceType.TIME_OUT), vm.state.value)
    }

    /** A type without a resolved checkpoint is meaningless and must not be reachable. */
    @Test
    fun `choosing a type while still scanning is ignored`() = runTest {
        val vm = ScanViewModel(schedule = roster(), checkpoints = FakeCheckpoints())

        vm.onTypeChosen(AttendanceType.TIME_IN)

        assertEquals(ScanState.Scanning, vm.state.value)
    }

    @Test
    fun `choosing a type on a disabled checkpoint is ignored`() = runTest {
        val vm = ScanViewModel(schedule = roster(), checkpoints = FakeCheckpoints(mapOf("ROOF-OLD" to CheckpointResolution.Disabled(roofOld))))
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
        val vm = ScanViewModel(
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
        val vm = ScanViewModel(
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
        val vm = ScanViewModel(
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
        val vm = ScanViewModel(
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
        val vm = ScanViewModel(
            checkpoints = FakeCheckpoints(mapOf("CP-LIBRARY" to CheckpointResolution.Resolved(library))),
            schedule = FakeRoster(timedInAt = gateA.id),
        )

        vm.onCodeScanned("CP-LIBRARY")

        val state = vm.state.value as ScanState.ChoosingType
        assertEquals(ROVING_TYPES, state.allowedTypes)
    }

    /** A rest day is not an error, and must not be worded as one. */
    @Test
    fun `a guard with no shift today is told so, not shown a scanner error`() = runTest {
        val vm = ScanViewModel(
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
        val vm = ScanViewModel(
            checkpoints = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))),
            schedule = FakeRoster(linked = false),
        )

        vm.onCodeScanned("GATE-A")

        assertEquals(ScanState.NotOnRoster, vm.state.value)
    }
}
