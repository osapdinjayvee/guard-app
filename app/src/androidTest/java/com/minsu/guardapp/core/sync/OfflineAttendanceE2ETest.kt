package com.minsu.guardapp.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.AttendanceDao
import com.minsu.guardapp.core.database.AttendanceEntity
import com.minsu.guardapp.core.database.GuardDatabase
import com.minsu.guardapp.core.database.SyncStatus
import com.minsu.guardapp.core.network.ApiError
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.core.network.dto.AttendanceDto
import com.minsu.guardapp.data.DefaultAttendanceRepository
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.domain.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * The offline-first promise, end to end: a valid attendance is never lost to connectivity.
 *
 * These drive the real write path — [DefaultAttendanceRepository.submit] into Room, then the actual
 * worker — rather than hand-placing rows in the database. The bugs worth catching here live in the
 * seam between those two, and a test that skips the seam cannot see them.
 */
@RunWith(AndroidJUnit4::class)
class OfflineAttendanceE2ETest {

    private lateinit var db: GuardDatabase
    private val dao get() = db.attendanceDao()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val t0 = 1_783_663_331_000L

    private val guard = GuardProfile(7, "Juan Dela Cruz", "guard_juan")

    private class FakeProfiles(private val profile: GuardProfile?) : ProfileRepository {
        override fun observe(): Flow<GuardProfile?> = MutableStateFlow(profile)
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
        override suspend fun clear() = Unit
    }

    private class RecordingScheduler : SyncScheduler {
        var requests = 0
        override fun requestSync() { requests++ }
        override fun syncNow() { requests++ }
        override fun observeSyncing(): Flow<Boolean> = MutableStateFlow(false)
    }

    /**
     * Stands in for the network. [online] is the airplane-mode switch; every upload while it is
     * false fails the way a real one does, with an IOException from the socket layer.
     */
    private class FakeNetwork : AttendanceUploader {
        var online = false
        /**
         * The client_uuid of every upload attempt, in order. Duplicates here are the bug.
         * Concurrent-safe: two workers genuinely race for this in the CAS test.
         */
        val attempts = CopyOnWriteArrayList<String>()
        var respondWith: ((AttendanceEntity) -> ApiResult<AttendanceDto>)? = null

        override suspend fun upload(record: AttendanceEntity): ApiResult<AttendanceDto> {
            attempts += record.id
            respondWith?.let { return it(record) }
            return if (online) {
                ApiResult.Success(dto(serverId = 500, clientUuid = record.id))
            } else {
                ApiResult.Failure(ApiError.Network(IOException("no route to host")))
            }
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, GuardDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun repository(scheduler: SyncScheduler, now: Long = t0) =
        DefaultAttendanceRepository(dao, FakeProfiles(guard), scheduler, Clock { now })

    private fun draft(capturedAt: Long = t0) = AttendanceDraft(
        checkpointId = 1,
        checkpointCode = "CP-MAIN-GATE",
        type = AttendanceType.TIME_IN,
        selfiePath = context.filesDir.resolve("selfie.jpg").absolutePath,
        capturedAtMillis = capturedAt,
        latitude = 13.1775,
        longitude = 121.2803,
        accuracyMetres = 8.4f,
        dutiesVersionId = 1,
    )

    /**
     * Suspending, not wrapped in its own `runBlocking`. That matters for the concurrency test
     * below: a nested `runBlocking` would block the caller's thread until the drain finished, so
     * two "parallel" workers would in fact run one after the other and the race would never happen.
     */
    private suspend fun runWorker(
        uploader: AttendanceUploader,
        now: Long = t0,
        dao: AttendanceDao = this.dao,
    ): ListenableWorker.Result =
        TestListenableWorkerBuilder<AttendanceSyncWorker>(context)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    params: WorkerParameters,
                ) = AttendanceSyncWorker(appContext, params, dao, uploader, Clock { now })
            })
            .build()
            .doWork()

    /**
     * The headline case. A guard captures an attendance in a basement, walks out, and it uploads.
     * Nothing in between is allowed to lose it.
     */
    @Test
    fun a_capture_taken_offline_survives_and_syncs_on_reconnect() = runBlocking {
        val network = FakeNetwork().apply { online = false }
        val id = UUID.randomUUID().toString()

        // Airplane mode: the capture is committed locally, with no network involved at all.
        repository(RecordingScheduler()).submit(id, draft())

        assertEquals(
            "the record must be durable the instant submit() returns",
            SyncStatus.PENDING,
            dao.byId(id)!!.syncStatus,
        )

        // A drain attempted while still offline must not lose or discard it.
        runWorker(network)
        val offline = dao.byId(id)!!
        assertEquals("an unreachable server is transient, never terminal", SyncStatus.FAILED, offline.syncStatus)
        assertNotNull("it must be scheduled to try again", offline.nextAttemptAt)

        // Signal returns. The backoff has elapsed, so the next drain claims it.
        network.online = true
        val result = runWorker(network, now = t0 + 60 * 60 * 1000)

        val synced = dao.byId(id)!!
        assertEquals(SyncStatus.SYNCED, synced.syncStatus)
        assertEquals(500L, synced.serverId)
        assertNull("a synced record owes nothing more", synced.lastError)
        assertTrue(result is ListenableWorker.Result.Success)
    }

    /**
     * The lost response: the server stored the attendance and answered, and the answer never
     * arrived — a dropped connection on the way back, which is ordinary on a bad network.
     *
     * The client cannot tell this apart from an upload that never landed, so it retries. What makes
     * that safe is that the retry carries *the same* client_uuid: the server recognises it, returns
     * the record it already holds, and the guard ends up with one attendance rather than two. If
     * the client minted a fresh id per attempt, this test would pass and production would quietly
     * double-count every flaky upload.
     */
    @Test
    fun a_lost_response_is_retried_under_the_same_idempotency_key_and_does_not_double_record() =
        runBlocking {
            val network = FakeNetwork()
            val id = UUID.randomUUID().toString()

            repository(RecordingScheduler()).submit(id, draft())

            // Attempt 1: the server commits it, then the connection dies before we hear back.
            network.respondWith = { ApiResult.Failure(ApiError.Network(IOException("connection reset"))) }
            runWorker(network)
            assertEquals(SyncStatus.FAILED, dao.byId(id)!!.syncStatus)

            // Attempt 2: the server sees a client_uuid it already has and replays the stored record.
            network.respondWith = { record ->
                ApiResult.Success(dto(serverId = 900, clientUuid = record.id))
            }
            runWorker(network, now = t0 + 60 * 60 * 1000)

            val synced = dao.byId(id)!!
            assertEquals(SyncStatus.SYNCED, synced.syncStatus)
            assertEquals("the replayed record is the same one, not a new one", 900L, synced.serverId)

            assertEquals("both attempts must carry the same idempotency key", listOf(id, id), network.attempts)
            assertEquals("a retry must never create a second local record", 1, dao.count())
        }

    /**
     * The CAS claim, tested where it actually bites.
     *
     * Both workers are held until they have *both* listed the same PENDING record — the window
     * between listing and claiming, which is the only window the compare-and-swap exists for. They
     * are then released to race for it. Exactly one may upload.
     *
     * This is deliberately not the same test as [a_record_being_uploaded_is_invisible_to_a_second_drain]
     * below, which passes even with the CAS removed: once a record is SYNCING it is filtered out of
     * the listing query and a second worker never reaches the claim at all. That filter is a real
     * protection but it is not this one, and a test that cannot tell them apart would let a broken
     * claim ship.
     */
    @Test
    fun two_drains_that_both_list_the_same_record_upload_it_once() = runBlocking {
        val id = UUID.randomUUID().toString()
        repository(RecordingScheduler()).submit(id, draft())

        val network = FakeNetwork().apply { online = true }
        val bothListed = CountDownLatch(2)

        // Holds each worker after it has listed the record but before it claims it, until the other
        // has listed it too. Without this the first worker claims and uploads before the second even
        // runs its query, and the race never happens.
        val gated = object : AttendanceDao by dao {
            override suspend fun eligibleForSync(
                now: Long,
                limit: Int,
                statuses: List<SyncStatus>,
            ): List<AttendanceEntity> {
                val rows = dao.eligibleForSync(now, limit, statuses)
                bothListed.countDown()
                withContext(Dispatchers.IO) { bothListed.await() }
                return rows
            }
        }

        listOf(
            async(Dispatchers.IO) { runWorker(network, dao = gated) },
            async(Dispatchers.IO) { runWorker(network, dao = gated) },
        ).awaitAll()

        assertEquals("the CAS claim must admit exactly one uploader", listOf(id), network.attempts.toList())
        assertEquals(SyncStatus.SYNCED, dao.byId(id)!!.syncStatus)
        assertEquals(1, dao.count())
    }

    /**
     * A record already in flight is not listed again.
     *
     * The second worker runs while the first is provably mid-upload, holding the record in SYNCING.
     * This is the listing filter's job, not the claim's — see the test above.
     */
    @Test
    fun a_record_being_uploaded_is_invisible_to_a_second_drain() = runBlocking {
        val id = UUID.randomUUID().toString()
        repository(RecordingScheduler()).submit(id, draft())

        val uploadStarted = CountDownLatch(1)
        val releaseUpload = CountDownLatch(1)
        val slowAttempts = CopyOnWriteArrayList<String>()

        val slowNetwork = object : AttendanceUploader {
            override suspend fun upload(record: AttendanceEntity): ApiResult<AttendanceDto> {
                slowAttempts += record.id
                uploadStarted.countDown()
                withContext(Dispatchers.IO) { releaseUpload.await() }
                return ApiResult.Success(dto(serverId = 500, clientUuid = record.id))
            }
        }
        val secondDrain = FakeNetwork().apply { online = true }

        val first = async(Dispatchers.IO) { runWorker(slowNetwork) }

        // Wait until the first worker has claimed the record and is inside the upload.
        withContext(Dispatchers.IO) { uploadStarted.await() }
        assertEquals(SyncStatus.SYNCING, dao.byId(id)!!.syncStatus)

        // A second drain, right now, with the record in flight.
        runWorker(secondDrain)

        releaseUpload.countDown()
        first.await()

        assertEquals("a claimed record must be invisible to a second drain", 0, secondDrain.attempts.size)
        assertEquals("exactly one upload, ever", listOf(id), slowAttempts.toList())
        assertEquals(SyncStatus.SYNCED, dao.byId(id)!!.syncStatus)
        assertEquals(1, dao.count())
    }

    /**
     * A rejected record is the guard's to see and to act on. It is never retried automatically and
     * never deleted — the selfie and the capture time are evidence — but "Sync now" must be able to
     * put it back in the queue once whatever caused the rejection has been fixed.
     */
    @Test
    fun sync_now_un_sticks_a_rejected_record_and_uploads_it() = runBlocking {
        val network = FakeNetwork()
        val scheduler = RecordingScheduler()
        val repo = repository(scheduler)
        val id = UUID.randomUUID().toString()

        repo.submit(id, draft())

        // The server refuses it: the checkpoint had been disabled.
        network.respondWith = {
            ApiResult.Failure(ApiError.Rejected("checkpoint_inactive", "This checkpoint is no longer active."))
        }
        runWorker(network)

        val rejected = dao.byId(id)!!
        assertEquals(SyncStatus.REJECTED, rejected.syncStatus)
        assertNull("a rejection must not schedule an automatic retry", rejected.nextAttemptAt)

        // An admin re-enables the checkpoint; the guard taps Sync now.
        val requeued = repo.syncNow()
        assertEquals(1, requeued)
        assertEquals(SyncStatus.PENDING, dao.byId(id)!!.syncStatus)

        network.respondWith = null
        network.online = true
        runWorker(network, now = t0 + 1000)

        assertEquals(SyncStatus.SYNCED, dao.byId(id)!!.syncStatus)
        assertEquals("still one record, and one selfie", 1, dao.count())
    }
}

private fun dto(serverId: Long, clientUuid: String) = AttendanceDto(
    id = serverId,
    clientUuid = clientUuid,
    userId = 7,
    checkpointId = 1,
    attendanceType = "time_in",
    selfieUrl = "https://guard.minsu.edu.ph/storage/selfies/$serverId.jpg",
    capturedAt = "2026-07-11T06:15:00+08:00",
    receivedAt = "2026-07-11T14:02:11+08:00",
    dutiesAcknowledged = true,
)
