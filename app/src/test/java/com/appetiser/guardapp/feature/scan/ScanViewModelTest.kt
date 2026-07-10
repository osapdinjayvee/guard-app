package com.appetiser.guardapp.feature.scan

import com.appetiser.guardapp.core.network.ApiResult
import com.appetiser.guardapp.domain.model.Checkpoint
import com.appetiser.guardapp.domain.model.CheckpointResolution
import com.appetiser.guardapp.domain.repository.CheckpointRepository
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
        override fun observeActive(): Flow<List<Checkpoint>> = MutableStateFlow(emptyList())
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    private val gateA = Checkpoint(1, "GATE-A", "Main Gate", isActive = true, latitude = null, longitude = null)
    private val roofOld = Checkpoint(4, "ROOF-OLD", "Rooftop", isActive = false, latitude = null, longitude = null)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `starts scanning`() = runTest {
        assertEquals(ScanState.Scanning, ScanViewModel(FakeCheckpoints()).state.value)
    }

    @Test
    fun `an active checkpoint resolves`() = runTest {
        val vm = ScanViewModel(FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA))))

        vm.onCodeScanned("GATE-A")

        assertEquals(ScanState.Resolved(gateA), vm.state.value)
    }

    /** A retired checkpoint must not read as "unrecognised code". */
    @Test
    fun `a disabled checkpoint is reported as retired, not unknown`() = runTest {
        val vm = ScanViewModel(FakeCheckpoints(mapOf("ROOF-OLD" to CheckpointResolution.Disabled(roofOld))))

        vm.onCodeScanned("ROOF-OLD")

        assertTrue(vm.state.value is ScanState.Disabled)
    }

    @Test
    fun `an unknown code is reported as unknown`() = runTest {
        val vm = ScanViewModel(FakeCheckpoints())

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
        val vm = ScanViewModel(repo)

        vm.onCodeScanned("GATE-A")
        vm.onCodeScanned("GATE-A")
        vm.onCodeScanned("GATE-B")

        assertEquals(1, repo.resolveCalls)
        assertEquals(ScanState.Resolved(gateA), vm.state.value)
    }

    @Test
    fun `scan again re-arms the scanner`() = runTest {
        val repo = FakeCheckpoints(mapOf("GATE-A" to CheckpointResolution.Resolved(gateA)))
        val vm = ScanViewModel(repo)
        vm.onCodeScanned("GATE-A")

        vm.scanAgain()

        assertEquals(ScanState.Scanning, vm.state.value)
        vm.onCodeScanned("GATE-A")
        assertEquals(2, repo.resolveCalls)
    }
}
