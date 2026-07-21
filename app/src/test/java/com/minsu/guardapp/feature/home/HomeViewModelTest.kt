package com.minsu.guardapp.feature.home

import com.minsu.guardapp.core.connectivity.NetworkMonitor
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.model.Announcement
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.domain.model.SyncState
import com.minsu.guardapp.domain.repository.AnnouncementRepository
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.DutyRepository
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.model.EvaluationQuestion
import com.minsu.guardapp.domain.repository.EvaluationRepository
import com.minsu.guardapp.domain.repository.ScheduleRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val profiles = MutableStateFlow<GuardProfile?>(null)
    private val pending = MutableStateFlow(0)
    private val history = MutableStateFlow<List<AttendanceRecord>>(emptyList())
    private val settings = MutableStateFlow(AppSettings())
    private val notices = MutableStateFlow<List<Announcement>>(emptyList())
    private val online = MutableStateFlow(true)

    private var checkpointRefreshes = 0
    private var dutyRefreshes = 0

    private val profileRepo = object : ProfileRepository {
        override fun observe(): Flow<GuardProfile?> = profiles
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
        override suspend fun clear() = Unit
    }
    private val attendanceRepo = object : AttendanceRepository {
        override fun observeUnsyncedCount(): Flow<Int> = pending
        override fun observeOtherAccountUnsyncedCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeHistory(limit: Int): Flow<List<AttendanceRecord>> = history
        override suspend fun submit(id: String, draft: com.minsu.guardapp.domain.model.AttendanceDraft) = Unit
        override fun observeRecord(id: String): Flow<AttendanceRecord?> = MutableStateFlow(null)
        override suspend fun retry(id: String) = Unit
        override fun observeStuckCount(): Flow<Int> = MutableStateFlow(0)
        override suspend fun syncNow(): Int = 0
        override fun observeInRange(fromMillis: Long, toMillis: Long): Flow<List<AttendanceRecord>> = MutableStateFlow(emptyList())
        override suspend fun checkpointVisitsToday(): Map<Long, Int> = emptyMap()
        override suspend fun lastVisitedCheckpointToday(): Long? = null
        override suspend fun hasTimedOutToday(): Boolean = false
    }
    private val settingsRepo = object : SettingsRepository {
        override fun observe(): Flow<AppSettings> = settings
        override suspend fun current(): AppSettings = settings.value
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }
    private val announcementRepo = object : AnnouncementRepository {
        override fun observe(): Flow<List<Announcement>> = notices
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }
    private val checkpointRepo = object : CheckpointRepository {
        override suspend fun resolve(code: String): CheckpointResolution = CheckpointResolution.Unknown(code)
        override suspend fun byId(id: Long): Checkpoint? = null
        override fun observeActive(): Flow<List<Checkpoint>> = MutableStateFlow(emptyList())
        override suspend fun refresh(): ApiResult<Unit> {
            checkpointRefreshes++
            return ApiResult.Success(Unit)
        }
    }
    private val dutyRepo = object : DutyRepository {
        override suspend fun activeDuty(): Duty? = null
        override suspend fun refresh(): ApiResult<Unit> {
            dutyRefreshes++
            return ApiResult.Success(Unit)
        }
    }
    private var scheduleRefreshes = 0
    private val scheduleRepo = object : ScheduleRepository {
        override fun observeToday(): Flow<DutyAssignment?> = MutableStateFlow(null)
        override fun observeAll(): Flow<List<DutyAssignment>> = MutableStateFlow(emptyList())
        override suspend fun today(): DutyAssignment? = null
        override val isLinked: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun postTimedInAtToday(): Long? = null
        override suspend fun refresh(): ApiResult<Unit> {
            scheduleRefreshes++
            return ApiResult.Success(Unit)
        }
    }
    private var evaluationRefreshes = 0
    private val evaluationRepo = object : EvaluationRepository {
        override suspend fun questions(): List<EvaluationQuestion> = emptyList()
        override suspend fun refresh(): ApiResult<Unit> {
            evaluationRefreshes++
            return ApiResult.Success(Unit)
        }
    }
    private val monitor = object : NetworkMonitor {
        override val isOnline: Flow<Boolean> = online
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        HomeViewModel(profileRepo, attendanceRepo, settingsRepo, announcementRepo, checkpointRepo, dutyRepo, scheduleRepo, evaluationRepo, monitor)

    @Test
    fun `streams local state without waiting on the network`() = runTest {
        profiles.value = GuardProfile(7, "Juan Dela Cruz", "guard01")
        pending.value = 3
        notices.value = listOf(Announcement(1, "Notice", "Body"))

        val state = viewModel().uiState.value

        assertEquals("Juan Dela Cruz", state.profile?.name)
        assertEquals(3, state.pendingSyncCount)
        assertEquals(1, state.announcements.size)
    }

    @Test
    fun `pending count updates as records drain`() = runTest {
        val vm = viewModel()
        pending.value = 2
        assertEquals(2, vm.uiState.value.pendingSyncCount)

        pending.value = 0
        assertEquals(0, vm.uiState.value.pendingSyncCount)
    }

    @Test
    fun `surfaces the most recent record and its sync state`() = runTest {
        history.value = listOf(
            AttendanceRecord(
                id = "a", checkpointCode = "GATE-A", type = AttendanceType.TIME_IN,
                capturedAt = 1_752_000_000_000, selfiePath = "/x.jpg",
                latitude = null, longitude = null, accuracyMetres = null,
                syncState = SyncState.PENDING, lastError = null,
            )
        )

        val state = viewModel().uiState.value

        assertEquals("GATE-A", state.lastRecord?.checkpointCode)
        assertEquals(SyncState.PENDING, state.lastRecord?.syncState)
    }

    @Test
    fun `tracks connectivity`() = runTest {
        val vm = viewModel()
        assertTrue(vm.uiState.value.isOnline)

        online.value = false

        assertFalse(vm.uiState.value.isOnline)
    }

    @Test
    fun `shows the server maintenance message when one is set`() = runTest {
        settings.value = AppSettings(maintenanceMessage = "Back at 6am")

        assertEquals("Back at 6am", viewModel().uiState.value.maintenanceMessage)
    }

    /**
     * Every value on Home has a local or cached source, so a dead network must not blank the
     * screen or raise an error banner on an app designed to work offline.
     */
    @Test
    fun `a failed refresh leaves the cached screen intact`() = runTest {
        profiles.value = GuardProfile(7, "Juan Dela Cruz", "guard01")
        val failing = object : ProfileRepository {
            override fun observe(): Flow<GuardProfile?> = profiles
            override suspend fun refresh(): ApiResult<Unit> = ApiResult.Failure(
                com.minsu.guardapp.core.network.ApiError.Network(IOException("offline"))
            )
            override suspend fun clear() = Unit
        }

        val vm = HomeViewModel(failing, attendanceRepo, settingsRepo, announcementRepo, checkpointRepo, dutyRepo, scheduleRepo, evaluationRepo, monitor)

        assertEquals("Juan Dela Cruz", vm.uiState.value.profile?.name)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `refresh warms the checkpoint cache so scanning works offline later`() = runTest {
        viewModel()

        assertEquals("checkpoints must be cached on load", 1, checkpointRefreshes)
    }

    /**
     * The duties document is needed by the acknowledgement gate, which is the last step of a
     * capture and may well happen with no signal. If it is not cached while the app is online, a
     * guard can be left unable to submit an attendance they have already taken.
     */
    @Test
    fun `refresh warms the duties cache so the acknowledgement gate works offline later`() = runTest {
        viewModel()

        assertEquals("duties must be cached on load", 1, dutyRefreshes)
    }

    @Test
    fun `renders before a profile has ever been fetched`() = runTest {
        assertNull(viewModel().uiState.value.profile)
    }
}
