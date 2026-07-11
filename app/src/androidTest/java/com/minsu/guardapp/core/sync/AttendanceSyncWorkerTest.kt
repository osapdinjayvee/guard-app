package com.minsu.guardapp.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.AttendanceEntity
import com.minsu.guardapp.core.database.AttendanceType
import com.minsu.guardapp.core.database.GuardDatabase
import com.minsu.guardapp.core.database.SyncStatus
import com.minsu.guardapp.core.network.ApiError
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.dto.AttendanceDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AttendanceSyncWorkerTest {

    private lateinit var db: GuardDatabase
    private val dao get() = db.attendanceDao()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val t0 = 1_783_663_331_000L

    /** Scripted uploader: one result per record id, defaulting to a transient network error. */
    private class FakeUploader(
        private val results: MutableMap<String, ApiResult<AttendanceDto>> = mutableMapOf(),
        val uploaded: MutableList<String> = mutableListOf(),
    ) : AttendanceUploader {
        fun on(id: String, result: ApiResult<AttendanceDto>) = apply { results[id] = result }
        override suspend fun upload(record: AttendanceEntity): ApiResult<AttendanceDto> {
            uploaded += record.id
            return results[record.id] ?: ApiResult.Failure(ApiError.Network(IOException("offline")))
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, GuardDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun record(
        id: String,
        status: SyncStatus = SyncStatus.PENDING,
        claimedAt: Long? = null,
        retryCount: Int = 0,
    ) = AttendanceEntity(
        id = id,
        userId = 7,
        checkpointId = 1,
        checkpointCode = "GATE-A",
        attendanceType = AttendanceType.TIME_IN,
        selfiePath = context.filesDir.resolve("$id.jpg").absolutePath,
        capturedAt = t0,
        latitude = 14.6,
        longitude = 120.98,
        accuracy = 8f,
        dutiesAcknowledged = true,
        dutiesVersionId = 12,
        deviceId = null,
        syncStatus = status,
        retryCount = retryCount,
        claimedAt = claimedAt,
        createdAt = t0,
        updatedAt = t0,
    )

    private fun dto(id: Long) = AttendanceDto(
        id = id, clientUuid = "u", userId = 7, checkpointId = 1, attendanceType = "TIME_IN",
        selfieUrl = "https://x/$id.jpg", capturedAt = "t", receivedAt = "t", dutiesAcknowledged = true,
    )

    private fun runWorker(uploader: FakeUploader, now: Long = t0): ListenableWorker.Result = runBlocking {
        val worker = TestListenableWorkerBuilder<AttendanceSyncWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters) =
                    AttendanceSyncWorker(appContext, params, dao, uploader, Clock { now })
            })
            .build()
        worker.doWork()
    }

    @Test
    fun a_pending_record_uploads_and_becomes_synced() = runBlocking {
        dao.insert(record("a"))
        val uploader = FakeUploader().on("a", ApiResult.Success(dto(501)))

        val result = runWorker(uploader)

        assertEquals(ListenableWorker.Result.success(), result)
        val stored = dao.byId("a")!!
        assertEquals(SyncStatus.SYNCED, stored.syncStatus)
        assertEquals(501L, stored.serverId)
        assertEquals(listOf("a"), uploader.uploaded)
    }

    /** A 401 must not burn a retry or mark the record FAILED: it waits for re-authentication. */
    @Test
    fun a_401_releases_the_claim_back_to_pending_and_stops() = runBlocking {
        dao.insert(record("a"))
        dao.insert(record("b"))
        val uploader = FakeUploader().on("a", ApiResult.Failure(ApiError.Unauthorized))

        runWorker(uploader)

        val a = dao.byId("a")!!
        assertEquals(SyncStatus.PENDING, a.syncStatus)
        assertEquals(0, a.retryCount)
        assertNull(a.claimedAt)
        // Stopped after the 401 — the second record was never attempted.
        assertEquals(listOf("a"), uploader.uploaded)
    }

    /** A permanent 4xx is terminal; retrying would loop forever. */
    @Test
    fun a_permanent_rejection_becomes_rejected_and_is_not_retried() = runBlocking {
        dao.insert(record("a"))
        val uploader = FakeUploader().on("a", ApiResult.Failure(ApiError.Rejected("checkpoint_disabled", "retired")))

        val result = runWorker(uploader)

        val a = dao.byId("a")!!
        assertEquals(SyncStatus.REJECTED, a.syncStatus)
        assertNull("no auto-retry", a.nextAttemptAt)
        assertTrue(a.lastError!!.contains("checkpoint_disabled"))
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun a_transient_failure_becomes_failed_with_backoff_and_asks_to_retry() = runBlocking {
        dao.insert(record("a"))
        val uploader = FakeUploader().on("a", ApiResult.Failure(ApiError.Server(503, "down")))

        val result = runWorker(uploader, now = t0)

        val a = dao.byId("a")!!
        assertEquals(SyncStatus.FAILED, a.syncStatus)
        assertEquals(1, a.retryCount)
        assertTrue("backoff is in the future", a.nextAttemptAt!! > t0)
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    /** The T-25 hole: a record stranded in SYNCING by a crash is reclaimed and then uploaded. */
    @Test
    fun a_record_orphaned_in_syncing_is_reclaimed_and_synced() = runBlocking {
        // Claimed 10 minutes ago, well past the 5-minute stale threshold.
        dao.insert(record("a", status = SyncStatus.SYNCING, claimedAt = t0 - 10 * 60_000))
        val uploader = FakeUploader().on("a", ApiResult.Success(dto(777)))

        runWorker(uploader, now = t0)

        assertEquals(SyncStatus.SYNCED, dao.byId("a")!!.syncStatus)
        assertEquals(listOf("a"), uploader.uploaded)
    }

    /** A fresh in-flight claim must not be swept out from under a live upload. */
    @Test
    fun a_recently_claimed_record_is_left_in_flight() = runBlocking {
        dao.insert(record("a", status = SyncStatus.SYNCING, claimedAt = t0 - 30_000))
        val uploader = FakeUploader()

        runWorker(uploader, now = t0)

        assertEquals(SyncStatus.SYNCING, dao.byId("a")!!.syncStatus)
        assertTrue("not re-uploaded", uploader.uploaded.isEmpty())
    }

    @Test
    fun a_synced_record_is_not_uploaded_again() = runBlocking {
        dao.insert(record("a", status = SyncStatus.SYNCED))
        val uploader = FakeUploader()

        runWorker(uploader)

        assertTrue(uploader.uploaded.isEmpty())
    }
}
