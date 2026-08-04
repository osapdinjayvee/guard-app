package com.minsu.guardapp.data

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.AttendanceDao
import com.minsu.guardapp.core.database.AttendanceEntity
import com.minsu.guardapp.core.database.SyncStatus
import com.minsu.guardapp.core.network.ApiErrorMapper
import com.minsu.guardapp.core.network.GuardApi
import com.minsu.guardapp.core.network.dto.AttendanceDto
import com.minsu.guardapp.core.network.dto.PageMetaDto
import com.minsu.guardapp.core.network.dto.PagedEnvelope
import com.minsu.guardapp.core.sync.SyncScheduler
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.GuardProfile
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.core.network.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceSubmitTest {

    /** Records only the calls the write path is contracted to make, in order. */
    private class RecordingDao : AttendanceDao {
        val inserted = mutableListOf<AttendanceEntity>()
        override suspend fun insert(record: AttendanceEntity) { inserted += record }
        /** Mirrors Room's IGNORE: an id already present is left exactly as it was. */
        override suspend fun insertDownloaded(records: List<AttendanceEntity>): List<Long> =
            records.map { record ->
                if (inserted.any { it.id == record.id }) -1L
                else { inserted += record; 1L }
            }
        override suspend fun byId(id: String) = inserted.firstOrNull { it.id == id }
        override fun observeById(id: String): Flow<AttendanceEntity?> = MutableStateFlow(inserted.firstOrNull { it.id == id })
        override suspend fun requeue(id: String, now: Long) = 0
        var requeueAllCalls = 0
        override suspend fun requeueAll(now: Long): Int { requeueAllCalls++; return 0 }
        override fun observeStuckCount(userId: Long): Flow<Int> = MutableStateFlow(0)
        override fun observeRejectedCount(userId: Long): Flow<Int> = MutableStateFlow(0)
        override suspend fun rejected(userId: Long): List<AttendanceEntity> =
            inserted.filter { it.userId == userId && it.syncStatus == SyncStatus.REJECTED }
        override suspend fun deleteRejected(userId: Long): Int {
            val doomed = inserted.filter { it.userId == userId && it.syncStatus == SyncStatus.REJECTED }
            inserted.removeAll(doomed.toSet())
            return doomed.size
        }
        override fun observeInRange(userId: Long, fromMillis: Long, toMillis: Long): Flow<List<AttendanceEntity>> =
            MutableStateFlow(inserted.filter { it.userId == userId })
        override fun observePage(userId: Long, limit: Int, offset: Int): Flow<List<AttendanceEntity>> =
            MutableStateFlow(inserted.filter { it.userId == userId })
        override fun observeUnsyncedCount(userId: Long, statuses: List<SyncStatus>): Flow<Int> = MutableStateFlow(0)
        override fun observeOtherAccountUnsyncedCount(userId: Long, statuses: List<SyncStatus>): Flow<Int> = MutableStateFlow(0)
        override suspend fun eligibleForSync(userId: Long, now: Long, limit: Int, statuses: List<SyncStatus>) =
            emptyList<AttendanceEntity>()
        override suspend fun claim(id: String, now: Long, statuses: List<SyncStatus>) = 0
        override suspend fun reclaimStaleClaims(staleBefore: Long, now: Long) = 0
        override suspend fun markSynced(id: String, serverId: Long, now: Long) = Unit
        override suspend fun markFailed(id: String, error: String, nextAttemptAt: Long, now: Long) = Unit
        override suspend fun markRejected(id: String, error: String, now: Long) = Unit
        override suspend fun releaseClaim(id: String, now: Long) = Unit
        override suspend fun firstTimeInBetween(userId: Long, fromMillis: Long, toMillis: Long): AttendanceEntity? =
            inserted.firstOrNull {
                it.userId == userId &&
                    it.attendanceType == com.minsu.guardapp.core.database.AttendanceType.TIME_IN &&
                    it.capturedAt in fromMillis until toMillis
            }
        override suspend fun firstTimeOutBetween(userId: Long, fromMillis: Long, toMillis: Long): AttendanceEntity? =
            inserted.firstOrNull {
                it.userId == userId &&
                    it.attendanceType == com.minsu.guardapp.core.database.AttendanceType.TIME_OUT &&
                    it.capturedAt in fromMillis until toMillis
            }
        override suspend fun checkpointVisitCountsBetween(
            userId: Long,
            fromMillis: Long,
            toMillis: Long,
        ): List<com.minsu.guardapp.core.database.CheckpointVisitCount> =
            inserted
                .filter {
                    it.userId == userId &&
                        it.attendanceType == com.minsu.guardapp.core.database.AttendanceType.CHECKPOINT &&
                        it.capturedAt in fromMillis until toMillis
                }
                .groupBy { it.checkpointId }
                .map { (id, rows) -> com.minsu.guardapp.core.database.CheckpointVisitCount(id, rows.size) }
        override suspend fun lastVisitedCheckpointBetween(userId: Long, fromMillis: Long, toMillis: Long): Long? =
            inserted
                .filter {
                    it.userId == userId &&
                        it.attendanceType == com.minsu.guardapp.core.database.AttendanceType.CHECKPOINT &&
                        it.capturedAt in fromMillis until toMillis
                }
                .maxByOrNull { it.capturedAt }
                ?.checkpointId
        override suspend fun count() = inserted.size
    }

    private class RecordingScheduler : SyncScheduler {
        var syncRequests = 0
        var syncNowRequests = 0
        override fun requestSync() { syncRequests++ }
        override fun syncNow() { syncNowRequests++ }
        override fun observeSyncing(): Flow<Boolean> = MutableStateFlow(false)
    }

    private class FakeProfiles(private val profile: GuardProfile?) : ProfileRepository {
        override fun observe(): Flow<GuardProfile?> = MutableStateFlow(profile)
        override suspend fun refresh(): ApiResult<Unit> = ApiResult.Success(Unit)
        override suspend fun clear() = Unit
    }

    private val draft = AttendanceDraft(
        checkpointId = 1,
        checkpointCode = "GATE-A",
        type = AttendanceType.TIME_IN,
        selfiePath = "/data/user/0/pkg/files/attendance/abc.jpg",
        capturedAtMillis = 1_783_663_331_000,
        latitude = 14.599512,
        longitude = 120.984222,
        accuracyMetres = 8.4f,
        dutiesVersionId = 12,
    )

    /** A stored record, pared down to the three things the discard rules turn on. */
    private fun entity(id: String, userId: Long, status: SyncStatus) = AttendanceEntity(
        id = id,
        userId = userId,
        checkpointId = 1,
        checkpointCode = "GATE-A",
        attendanceType = com.minsu.guardapp.core.database.AttendanceType.TIME_IN,
        // A path that does not exist. Deleting it is expected to fail harmlessly — the row must go
        // regardless, or a missing photo would strand the record it belongs to forever.
        selfiePath = "/no/such/file/$id.jpg",
        capturedAt = 1_783_663_331_000,
        latitude = 14.599512,
        longitude = 120.984222,
        accuracy = 8.4f,
        dutiesAcknowledged = false,
        dutiesVersionId = null,
        deviceId = null,
        syncStatus = status,
        createdAt = 1_783_663_331_000,
        updatedAt = 1_783_663_331_000,
    )

    private fun repo(
        dao: AttendanceDao,
        scheduler: SyncScheduler,
        profile: GuardProfile?,
        api: GuardApi = FakeGuardApi(),
    ) = DefaultAttendanceRepository(
        dao,
        FakeProfiles(profile),
        scheduler,
        EvaluationJson(com.squareup.moshi.Moshi.Builder().build()),
        api,
        ApiErrorMapper(com.squareup.moshi.Moshi.Builder().build()),
        Clock { 5_000L },
    )

    /**
     * "Sync now" has to do both halves. Re-queueing without draining leaves the records sitting
     * there; draining without re-queueing skips the FAILED and REJECTED rows entirely, which are
     * the very ones a guard taps the button about.
     */
    @Test
    fun `sync now un-sticks failed records and forces a drain`() = runTest {
        val dao = RecordingDao()
        val scheduler = RecordingScheduler()

        repo(dao, scheduler, GuardProfile(1, "Juan", "guard01")).syncNow()

        assertEquals("stuck records must be re-queued", 1, dao.requeueAllCalls)
        assertEquals("the drain must be forced, not merely requested", 1, scheduler.syncNowRequests)
        assertEquals("the KEEP-policy path would be dropped behind a stale request", 0, scheduler.syncRequests)
    }

    @Test
    fun `submit writes the record as PENDING then requests a sync`() = runTest {
        val dao = RecordingDao()
        val scheduler = RecordingScheduler()

        repo(dao, scheduler, GuardProfile(7, "Juan Dela Cruz", "guard01"))
            .submit("abc", draft)

        assertEquals(1, dao.inserted.size)
        val record = dao.inserted.single()
        assertEquals("abc", record.id)
        assertEquals(SyncStatus.PENDING, record.syncStatus)
        assertEquals(7L, record.userId)
        assertEquals("GATE-A", record.checkpointCode)
        // The duties acknowledgement is no longer a gate on the capture. The column survives for
        // records written under the old rule; nothing sets it now, and claiming otherwise would be
        // recording consent nobody gave.
        assertFalse("nothing is acknowledged any more", record.dutiesAcknowledged)
        assertEquals(12L, record.dutiesVersionId)
        assertEquals(1, scheduler.syncRequests)
    }

    /**
     * Discarding takes the rejected and nothing else.
     *
     * This is the only place in the app that destroys an attendance, so the boundary matters more
     * than the deletion does. PENDING is still going up; FAILED is a transient cause that the next
     * pass may well clear. Only REJECTED has been given a reason by the server and will be given
     * the same one forever.
     */
    @Test
    fun `discard removes rejected records and leaves everything else`() = runTest {
        val dao = RecordingDao()
        dao.inserted += entity("pending", 7, SyncStatus.PENDING)
        dao.inserted += entity("failed", 7, SyncStatus.FAILED)
        dao.inserted += entity("rejected-1", 7, SyncStatus.REJECTED)
        dao.inserted += entity("rejected-2", 7, SyncStatus.REJECTED)
        dao.inserted += entity("synced", 7, SyncStatus.SYNCED)

        val discarded = repo(dao, RecordingScheduler(), GuardProfile(7, "Juan", "guard01"))
            .discardRejected()

        assertEquals(2, discarded)
        assertEquals(
            listOf("pending", "failed", "synced"),
            dao.inserted.map { it.id },
        )
    }

    /** A shared handset. Another guard's stranded records are their evidence, not this one's. */
    @Test
    fun `discard leaves another account's rejected records alone`() = runTest {
        val dao = RecordingDao()
        dao.inserted += entity("mine", 7, SyncStatus.REJECTED)
        dao.inserted += entity("theirs", 9, SyncStatus.REJECTED)

        val discarded = repo(dao, RecordingScheduler(), GuardProfile(7, "Juan", "guard01"))
            .discardRejected()

        assertEquals(1, discarded)
        assertEquals(listOf("theirs"), dao.inserted.map { it.id })
    }

    /** Signed out, every read is scoped to an id no guard has. Deleting must respect that too. */
    @Test
    fun `discard deletes nothing when nobody is signed in`() = runTest {
        val dao = RecordingDao()
        dao.inserted += entity("rejected", 7, SyncStatus.REJECTED)

        val discarded = repo(dao, RecordingScheduler(), profile = null).discardRejected()

        assertEquals(0, discarded)
        assertEquals(1, dao.inserted.size)
    }

    /** The id is the idempotency key, so the caller-supplied UUID must be the primary key. */
    @Test
    fun `submit uses the supplied id as the record key`() = runTest {
        val dao = RecordingDao()
        repo(dao, RecordingScheduler(), GuardProfile(7, "Juan", "guard01"))
            .submit("the-idempotency-key", draft)

        assertEquals("the-idempotency-key", dao.inserted.single().id)
    }

    /**
     * If the local write throws, nothing is enqueued: there is no record to sync, and a phantom
     * sync request would waste a wake-up.
     */
    @Test
    fun `a failed insert does not request a sync`() = runTest {
        val throwingDao = object : AttendanceDao by RecordingDao() {
            override suspend fun insert(record: AttendanceEntity) = throw RuntimeException("disk full")
        }
        val scheduler = RecordingScheduler()

        val result = runCatching {
            repo(throwingDao, scheduler, GuardProfile(7, "Juan", "guard01")).submit("abc", draft)
        }

        assertTrue(result.isFailure)
        assertEquals("no sync scheduled for a record that was never committed", 0, scheduler.syncRequests)
    }

    /**
     * A record the app cannot attribute to a signed-in guard is worse than no record: every history
     * and report query is scoped to the logged-in id, so one written under NO_USER (-1) is saved to
     * the table and then shown to nobody — the "recorded, but nowhere in Reports" the guards hit. It
     * must be refused loudly so the guard can retry, not written and silently lost.
     */
    @Test
    fun `submit refuses a record when nobody is signed in`() = runTest {
        val dao = RecordingDao()
        val scheduler = RecordingScheduler()

        val result = runCatching {
            repo(dao, scheduler, profile = null).submit("abc", draft)
        }

        assertTrue("a record with no owner must be refused, not written", result.isFailure)
        assertTrue("nothing may be inserted under NO_USER", dao.inserted.isEmpty())
        assertEquals("no sync for a record that was never committed", 0, scheduler.syncRequests)
    }

    /**
     * Two attendances at the same checkpoint, same type, same guard are two records — not one.
     *
     * This is the write-path half of the selfie-skip fix: each capture carries its own idempotency
     * key, so a guard timing in twice at GATE-A leaves two rows the server can tell apart, rather
     * than a second submission the server collapses into the first — or, as the UAT showed, a stale
     * "recorded" screen that wrote nothing at all.
     */
    @Test
    fun `two captures at the same checkpoint are stored as two separate records`() = runTest {
        val dao = RecordingDao()
        val repo = repo(dao, RecordingScheduler(), GuardProfile(7, "Juan", "guard01"))

        repo.submit("first-key", draft)
        repo.submit("second-key", draft)

        assertEquals("both captures must persist", 2, dao.inserted.size)
        assertEquals(setOf("first-key", "second-key"), dao.inserted.map { it.id }.toSet())
        assertTrue("both belong to the same guard", dao.inserted.all { it.userId == 7L })
    }

    /**
     * Clear the app's data, sign back in, and a guard's history must come back.
     *
     * History and Reports read only from Room, so a device that has never been told about a record
     * shows the guard nothing and reads as though their attendance had been lost with the app. It
     * had not been: the server has it, and this is what fetches it.
     */
    @Test
    fun `history is downloaded onto a device that has none`() = runTest {
        val dao = RecordingDao()
        val api = object : FakeGuardApi() {
            override suspend fun history(page: Int, perPage: Int) =
                PagedEnvelope(
                    data = listOf(serverRecord("uuid-1"), serverRecord("uuid-2", id = 502)),
                    meta = PageMetaDto(page = 1, perPage = 100, total = 2),
                )
        }

        val result = repo(dao, RecordingScheduler(), GuardProfile(7, "Juan", "guard01"), api)
            .refreshHistory()

        assertTrue(result is ApiResult.Success)
        assertEquals("both server records must land locally", 2, dao.inserted.size)
        val restored = dao.inserted.first { it.id == "uuid-1" }
        // Already on the server by definition, so it must never re-enter the upload queue.
        assertEquals(SyncStatus.SYNCED, restored.syncStatus)
        assertEquals(501L, restored.serverId)
        assertEquals(7L, restored.userId)
        assertEquals("GATE-A", restored.checkpointCode)
    }

    /**
     * The download fills gaps; it does not overrule the device.
     *
     * A capture still queued for upload is the one piece of state the server cannot know about, and
     * it holds the path to the photo on this phone. Letting the server's view replace it would mark
     * the record SYNCED, drop it out of the queue, and lose an attendance that was never uploaded —
     * the exact failure the offline-first write path exists to prevent.
     */
    @Test
    fun `a downloaded record never displaces one still waiting to upload`() = runTest {
        val dao = RecordingDao()
        val scheduler = RecordingScheduler()
        val repository = repo(dao, scheduler, GuardProfile(7, "Juan", "guard01"), object : FakeGuardApi() {
            override suspend fun history(page: Int, perPage: Int) =
                PagedEnvelope(
                    data = listOf(serverRecord("local-capture")),
                    meta = PageMetaDto(page = 1, perPage = 100, total = 1),
                )
        })

        repository.submit("local-capture", draft)
        repository.refreshHistory()

        val record = dao.inserted.single { it.id == "local-capture" }
        assertEquals("the queued capture must survive untouched", SyncStatus.PENDING, record.syncStatus)
        assertEquals("its local photo must not be swapped for a URL", draft.selfiePath, record.selfiePath)
    }

    /** Paging stops as soon as the server has no more to give, rather than running to the ceiling. */
    @Test
    fun `the backfill stops once every record has been fetched`() = runTest {
        var pagesRequested = 0
        val api = object : FakeGuardApi() {
            override suspend fun history(page: Int, perPage: Int): PagedEnvelope<AttendanceDto> {
                pagesRequested++
                return PagedEnvelope(
                    data = listOf(serverRecord("uuid-$page", id = page.toLong())),
                    meta = PageMetaDto(page = page, perPage = 1, total = 2),
                )
            }
        }

        repo(RecordingDao(), RecordingScheduler(), GuardProfile(7, "Juan", "guard01"), api)
            .refreshHistory()

        assertEquals("two records at one per page is two requests, not more", 2, pagesRequested)
    }

    /** Nobody signed in means nobody to file the records under, so none are fetched. */
    @Test
    fun `no history is downloaded when nobody is signed in`() = runTest {
        // FakeGuardApi throws on any unstubbed endpoint, so reaching the network fails this outright.
        val result = repo(RecordingDao(), RecordingScheduler(), profile = null).refreshHistory()

        assertTrue(result is ApiResult.Success)
    }

    private fun serverRecord(clientUuid: String, id: Long = 501) = AttendanceDto(
        id = id,
        clientUuid = clientUuid,
        userId = 7,
        checkpointId = 1,
        checkpointCode = "GATE-A",
        attendanceType = "time_in",
        selfieUrl = "https://guard.example/storage/selfies/$clientUuid.jpg",
        capturedAt = "2026-07-20T11:00:21+08:00",
        receivedAt = "2026-07-20T11:00:25+08:00",
        latitude = 14.599512,
        longitude = 120.984222,
        accuracy = 8.4f,
        dutiesAcknowledged = false,
        dutiesVersionId = 12,
        deviceId = null,
    )

    @Test
    fun `a null fix is stored as null coordinates, not zero`() = runTest {
        val dao = RecordingDao()
        repo(dao, RecordingScheduler(), GuardProfile(7, "Juan", "guard01"))
            .submit("abc", draft.copy(latitude = null, longitude = null, accuracyMetres = null))

        val record = dao.inserted.single()
        // 0.0 would place every location-less record off the coast of Africa; null must stay null.
        assertFalse(record.latitude == 0.0)
        assertEquals(null, record.latitude)
        assertEquals(null, record.accuracy)
    }
}
