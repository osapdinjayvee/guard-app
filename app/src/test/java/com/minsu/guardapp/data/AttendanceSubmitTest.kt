package com.minsu.guardapp.data

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.database.AttendanceDao
import com.minsu.guardapp.core.database.AttendanceEntity
import com.minsu.guardapp.core.database.SyncStatus
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
        override suspend fun byId(id: String) = inserted.firstOrNull { it.id == id }
        override fun observeById(id: String): Flow<AttendanceEntity?> = MutableStateFlow(inserted.firstOrNull { it.id == id })
        override suspend fun requeue(id: String, now: Long) = 0
        var requeueAllCalls = 0
        override suspend fun requeueAll(now: Long): Int { requeueAllCalls++; return 0 }
        override fun observeStuckCount(): Flow<Int> = MutableStateFlow(0)
        override fun observeInRange(fromMillis: Long, toMillis: Long): Flow<List<AttendanceEntity>> = MutableStateFlow(inserted)
        override fun observePage(limit: Int, offset: Int): Flow<List<AttendanceEntity>> = MutableStateFlow(inserted)
        override fun observeUnsyncedCount(statuses: List<SyncStatus>): Flow<Int> = MutableStateFlow(0)
        override suspend fun eligibleForSync(now: Long, limit: Int, statuses: List<SyncStatus>) = emptyList<AttendanceEntity>()
        override suspend fun claim(id: String, now: Long, statuses: List<SyncStatus>) = 0
        override suspend fun reclaimStaleClaims(staleBefore: Long, now: Long) = 0
        override suspend fun markSynced(id: String, serverId: Long, now: Long) = Unit
        override suspend fun markFailed(id: String, error: String, nextAttemptAt: Long, now: Long) = Unit
        override suspend fun markRejected(id: String, error: String, now: Long) = Unit
        override suspend fun releaseClaim(id: String, now: Long) = Unit
        override suspend fun firstTimeInBetween(fromMillis: Long, toMillis: Long): AttendanceEntity? =
            inserted.firstOrNull {
                it.attendanceType == com.minsu.guardapp.core.database.AttendanceType.TIME_IN &&
                    it.capturedAt in fromMillis until toMillis
            }
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

    private fun repo(dao: AttendanceDao, scheduler: SyncScheduler, profile: GuardProfile?) =
        DefaultAttendanceRepository(dao, FakeProfiles(profile), scheduler, Clock { 5_000L })

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
        assertTrue("duties must be acknowledged by the time we commit", record.dutiesAcknowledged)
        assertEquals(12L, record.dutiesVersionId)
        assertEquals(1, scheduler.syncRequests)
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
