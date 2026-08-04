package com.minsu.guardapp.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the sync state machine against real SQLite.
 *
 * Two tests here guard the failures identified in the architecture review: a record stranded
 * in SYNCING by process death, and a permanently rejected record looping forever in FAILED.
 */
@RunWith(AndroidJUnit4::class)
class AttendanceDaoTest {

    private lateinit var db: GuardDatabase
    private lateinit var dao: AttendanceDao

    private val t0 = 1_752_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GuardDatabase::class.java,
        ).build()
        dao = db.attendanceDao()
    }

    @After
    fun tearDown() = db.close()

    private fun record(
        id: String,
        status: SyncStatus = SyncStatus.PENDING,
        capturedAt: Long = t0,
        nextAttemptAt: Long? = null,
        claimedAt: Long? = null,
    ) = AttendanceEntity(
        id = id,
        userId = 7,
        checkpointId = 1,
        checkpointCode = "GATE-A",
        attendanceType = AttendanceType.TIME_IN,
        selfiePath = "/data/user/0/pkg/files/$id.jpg",
        capturedAt = capturedAt,
        latitude = 14.5995,
        longitude = 120.9842,
        accuracy = 8.4f,
        dutiesAcknowledged = true,
        dutiesVersionId = 12,
        deviceId = null,
        syncStatus = status,
        nextAttemptAt = nextAttemptAt,
        claimedAt = claimedAt,
        createdAt = capturedAt,
        updatedAt = capturedAt,
    )

    @Test
    fun a_record_survives_insert_and_is_pending_by_default() = runTest {
        dao.insert(record("a"))

        val stored = dao.byId("a")!!
        assertEquals(SyncStatus.PENDING, stored.syncStatus)
        assertNull(stored.serverId)
        assertEquals(1, dao.observeUnsyncedCount(userId = 7).first())
    }

    @Test
    fun claim_succeeds_once_and_then_refuses() = runTest {
        dao.insert(record("a"))

        assertEquals("first claim wins", 1, dao.claim("a", now = t0))
        assertEquals("second claim must not double-send", 0, dao.claim("a", now = t0))
        assertEquals(SyncStatus.SYNCING, dao.byId("a")!!.syncStatus)
    }

    @Test
    fun claim_refuses_a_terminal_record() = runTest {
        dao.insert(record("a"))
        dao.claim("a", now = t0)
        dao.markSynced("a", serverId = 501, now = t0)

        assertEquals(0, dao.claim("a", now = t0))
        assertEquals(SyncStatus.SYNCED, dao.byId("a")!!.syncStatus)
    }

    @Test
    fun a_failed_record_is_reclaimable_but_only_after_its_backoff() = runTest {
        dao.insert(record("a"))
        dao.claim("a", now = t0)
        dao.markFailed("a", error = "timeout", nextAttemptAt = t0 + 60_000, now = t0)

        assertEquals(1, dao.byId("a")!!.retryCount)
        assertTrue("still backing off", dao.eligibleForSync(userId = 7, now = t0 + 30_000).isEmpty())
        assertEquals("backoff elapsed", 1, dao.eligibleForSync(userId = 7, now = t0 + 60_000).size)
    }

    /** The orphaned-claim bug: a crash mid-upload must not lose the record. */
    @Test
    fun a_record_stranded_in_syncing_by_process_death_is_reclaimed() = runTest {
        dao.insert(record("a"))
        dao.claim("a", now = t0)
        // ...process dies here. The row is SYNCING, which the CAS predicate never re-selects.
        assertTrue(dao.eligibleForSync(userId = 7, now = t0 + 60_000).isEmpty())

        val swept = dao.reclaimStaleClaims(staleBefore = t0 + 30_000, now = t0 + 60_000)

        assertEquals(1, swept)
        assertEquals(SyncStatus.PENDING, dao.byId("a")!!.syncStatus)
        assertNull(dao.byId("a")!!.claimedAt)
        assertEquals("record is back in the queue", 1, dao.eligibleForSync(userId = 7, now = t0 + 60_000).size)
    }

    @Test
    fun the_sweep_leaves_a_fresh_claim_alone() = runTest {
        dao.insert(record("a"))
        dao.claim("a", now = t0 + 50_000)

        val swept = dao.reclaimStaleClaims(staleBefore = t0 + 30_000, now = t0 + 60_000)

        assertEquals("an in-flight upload must not be yanked back", 0, swept)
        assertEquals(SyncStatus.SYNCING, dao.byId("a")!!.syncStatus)
    }

    /** A permanently rejected record must not retry forever, and must not be deleted. */
    @Test
    fun a_rejected_record_is_terminal_but_retained() = runTest {
        dao.insert(record("a"))
        dao.claim("a", now = t0)
        dao.markRejected("a", error = "checkpoint_disabled", now = t0)

        val stored = dao.byId("a")!!
        assertEquals(SyncStatus.REJECTED, stored.syncStatus)
        assertNull("no automatic retry", stored.nextAttemptAt)
        assertEquals("checkpoint_disabled", stored.lastError)
        assertTrue("never auto-claimed again", dao.eligibleForSync(userId = 7, now = t0 + 10_000_000).isEmpty())
        assertEquals("evidence retained", "/data/user/0/pkg/files/a.jpg", stored.selfiePath)
        assertEquals(0, dao.claim("a", now = t0))
    }

    @Test
    fun rejected_and_synced_records_leave_the_pending_count() = runTest {
        dao.insert(record("a"))
        dao.insert(record("b"))
        dao.insert(record("c"))
        assertEquals(3, dao.observeUnsyncedCount(userId = 7).first())

        dao.claim("a", now = t0); dao.markSynced("a", serverId = 1, now = t0)
        dao.claim("b", now = t0); dao.markRejected("b", error = "out_of_sequence", now = t0)

        assertEquals("only the still-owed record counts", 1, dao.observeUnsyncedCount(userId = 7).first())
    }

    @Test
    fun eligible_records_come_back_oldest_capture_first() = runTest {
        dao.insert(record("new", capturedAt = t0 + 5_000))
        dao.insert(record("old", capturedAt = t0))

        val ids = dao.eligibleForSync(userId = 7, now = t0 + 10_000).map { it.id }

        assertEquals(listOf("old", "new"), ids)
    }
}
