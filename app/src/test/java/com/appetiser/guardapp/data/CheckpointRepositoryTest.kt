package com.appetiser.guardapp.data

import com.appetiser.guardapp.core.common.Clock
import com.appetiser.guardapp.core.database.CheckpointDao
import com.appetiser.guardapp.core.database.CheckpointEntity
import com.appetiser.guardapp.core.network.ApiErrorMapper
import com.appetiser.guardapp.core.network.ApiResult
import com.appetiser.guardapp.core.network.GuardApi
import com.appetiser.guardapp.core.network.dto.AnnouncementDto
import com.appetiser.guardapp.core.network.dto.AttendanceDto
import com.appetiser.guardapp.core.network.dto.CheckpointDto
import com.appetiser.guardapp.core.network.dto.DutyDto
import com.appetiser.guardapp.core.network.dto.Envelope
import com.appetiser.guardapp.core.network.dto.MobileSettingsDto
import com.appetiser.guardapp.core.network.dto.PagedEnvelope
import com.appetiser.guardapp.core.network.dto.ProfileDto
import com.appetiser.guardapp.domain.model.CheckpointResolution
import com.appetiser.guardapp.testing.FakeGuardApi
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CheckpointRepositoryTest {

    private class FakeCheckpointDao : CheckpointDao {
        val rows = MutableStateFlow<List<CheckpointEntity>>(emptyList())
        override suspend fun upsertAll(checkpoints: List<CheckpointEntity>) {
            val byId = rows.value.associateBy { it.id }.toMutableMap()
            checkpoints.forEach { byId[it.id] = it }
            rows.value = byId.values.toList()
        }
        override suspend fun findByCode(code: String): CheckpointEntity? =
            rows.value.firstOrNull { it.code == code }
        override fun observeActive(): Flow<List<CheckpointEntity>> =
            rows.map { list -> list.filter { it.status == "ACTIVE" } }
        override suspend fun count(): Int = rows.value.size
    }

    private val clock = Clock { 1_000L }
    private val errors = ApiErrorMapper(Moshi.Builder().build())
    private val dao = FakeCheckpointDao()

    private fun dto(id: Long, code: String, status: String) =
        CheckpointDto(id = id, code = code, name = "CP $code", status = status)

    private fun repo(api: GuardApi) = DefaultCheckpointRepository(dao, api, errors, clock)

    @Test
    fun `resolves a cached checkpoint with no network at all`() = runTest {
        dao.upsertAll(listOf(dto(1, "GATE-A", "ACTIVE").toEntity(0)))
        val repository = repo(object : FakeGuardApi() {
            override suspend fun checkpoints(): Envelope<List<CheckpointDto>> = throw IOException("offline")
        })

        val resolution = repository.resolve("GATE-A")

        assertTrue(resolution is CheckpointResolution.Resolved)
        assertEquals("GATE-A", (resolution as CheckpointResolution.Resolved).checkpoint.code)
    }

    /** A retired checkpoint must say "disabled", not "unrecognised". */
    @Test
    fun `a disabled checkpoint resolves as Disabled, not Unknown`() = runTest {
        dao.upsertAll(listOf(dto(4, "ROOF-OLD", "DISABLED").toEntity(0)))

        val resolution = repo(FakeGuardApi()).resolve("ROOF-OLD")

        assertTrue("$resolution", resolution is CheckpointResolution.Disabled)
    }

    @Test
    fun `an unknown code resolves as Unknown`() = runTest {
        val resolution = repo(FakeGuardApi()).resolve("NOT-A-CODE")

        assertEquals(CheckpointResolution.Unknown("NOT-A-CODE"), resolution)
    }

    @Test
    fun `refresh caches the server list`() = runTest {
        val repository = repo(object : FakeGuardApi() {
            override suspend fun checkpoints() =
                Envelope(listOf(dto(1, "GATE-A", "ACTIVE"), dto(4, "ROOF-OLD", "DISABLED")))
        })

        val result = repository.refresh()

        assertTrue(result is ApiResult.Success)
        assertEquals(2, dao.count())
        // Disabled checkpoints are cached too, so resolve() can tell them apart from unknown.
        assertTrue(repository.resolve("ROOF-OLD") is CheckpointResolution.Disabled)
        // ...but they are not offered as scannable.
        assertEquals(1, repository.observeActive().first().size)
    }

    /** Offline-first: a failed refresh must never leave the guard with an empty cache. */
    @Test
    fun `a failed refresh leaves the existing cache intact`() = runTest {
        dao.upsertAll(listOf(dto(1, "GATE-A", "ACTIVE").toEntity(0)))
        val repository = repo(object : FakeGuardApi() {
            override suspend fun checkpoints(): Envelope<List<CheckpointDto>> = throw IOException("offline")
        })

        val result = repository.refresh()

        assertTrue(result is ApiResult.Failure)
        assertEquals("cache survived", 1, dao.count())
        assertTrue(repository.resolve("GATE-A") is CheckpointResolution.Resolved)
    }
}
