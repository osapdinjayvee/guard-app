package com.minsu.guardapp.data

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.CheckpointDao
import com.minsu.guardapp.core.database.CheckpointEntity
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.dto.AnnouncementDto
import com.minsu.guardapp.core.network.dto.AttendanceDto
import com.minsu.guardapp.core.network.dto.CheckpointDto
import com.minsu.guardapp.core.network.dto.DutyDto
import com.minsu.guardapp.core.network.dto.Envelope
import com.minsu.guardapp.core.network.dto.MobileSettingsDto
import com.minsu.guardapp.core.network.dto.PagedEnvelope
import com.minsu.guardapp.core.network.dto.ProfileDto
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.testing.FakeGuardApi
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class CheckpointRepositoryTest {

    private class FakeCheckpointDao : CheckpointDao {
        val rows = MutableStateFlow<List<CheckpointEntity>>(emptyList())
        override suspend fun upsertAll(checkpoints: List<CheckpointEntity>) {
            val byId = rows.value.associateBy { it.id }.toMutableMap()
            checkpoints.forEach { byId[it.id] = it }
            rows.value = byId.values.toList()
        }
        // Mirrors the real query: code or slug, case-insensitively.
        override suspend fun findByCode(code: String): CheckpointEntity? =
            rows.value.firstOrNull {
                it.code.equals(code, ignoreCase = true) || it.slug.equals(code, ignoreCase = true)
            }
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

    /**
     * The sticker on the wall is a physical object. It may carry the slug rather than the code, and
     * `=` in SQLite is case-sensitive — a perfectly valid `clinic` must not miss a stored `CLINIC`
     * and be reported to the guard as a code that does not exist.
     */
    @Test
    fun `a scanned code matches on case and on the slug`() = runTest {
        dao.upsertAll(listOf(dto(2, "CLINIC", "ACTIVE").copy(slug = "clinic").toEntity(0)))
        val repository = repo(FakeGuardApi())

        assertTrue(repository.resolve("CLINIC") is CheckpointResolution.Resolved)
        assertTrue(repository.resolve("clinic") is CheckpointResolution.Resolved)
        assertTrue("whitespace off a QR payload", repository.resolve(" CLINIC\n") is CheckpointResolution.Resolved)
    }

    /**
     * A cache miss is a question, not an answer: the checkpoint may have been created since the
     * last refresh, or this device may never have refreshed at all. Ask the server, and cache what
     * it says so the next scan works offline.
     */
    @Test
    fun `a code missing from the cache is looked up on the server and cached`() = runTest {
        val repository = repo(object : FakeGuardApi() {
            override suspend fun checkpoint(code: String) = Envelope(dto(9, "CLINIC", "ACTIVE"))
        })

        val resolution = repository.resolve("CLINIC")

        assertTrue("$resolution", resolution is CheckpointResolution.Resolved)
        assertEquals("the answer is cached for the next, offline, scan", 1, dao.count())
    }

    /** Only the server, having actually looked, may call a code invalid. */
    @Test
    fun `a code the server has never heard of resolves as Unknown`() = runTest {
        val repository = repo(object : FakeGuardApi() {
            override suspend fun checkpoint(code: String): Envelope<CheckpointDto> =
                throw HttpException(Response.error<CheckpointDto>(404, "".toResponseBody()))
        })

        assertEquals(CheckpointResolution.Unknown("NOT-A-CODE"), repository.resolve("NOT-A-CODE"))
    }

    /**
     * The bug this whole path exists to prevent.
     *
     * An empty cache and a dead network is the state of every freshly-installed phone in a
     * basement. Telling that guard "this is not a checkpoint" is false, and it is the falsehood
     * that sends them looking for another door — or convinces them the sticker is broken. We could
     * not check, and we must say exactly that.
     */
    @Test
    fun `a cache miss we cannot verify is Unverifiable, never Unknown`() = runTest {
        val repository = repo(object : FakeGuardApi() {
            override suspend fun checkpoint(code: String): Envelope<CheckpointDto> =
                throw IOException("no route to host")
        })

        val resolution = repository.resolve("CLINIC")

        assertEquals(CheckpointResolution.Unverifiable("CLINIC"), resolution)
        assertTrue("an offline miss must never be reported as an invalid code",
            resolution !is CheckpointResolution.Unknown)
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
