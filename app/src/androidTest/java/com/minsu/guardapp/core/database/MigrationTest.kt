package com.minsu.guardapp.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrations must not lose attendance.
 *
 * This database is the only copy of a record between the shutter and the server. A migration that
 * drops and re-creates a table — which is what `fallbackToDestructiveMigration` does, silently, on
 * a device in the field — destroys exactly the evidence the offline-first design exists to protect.
 * So every migration is tested against a real database with a real unsynced record in it.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GuardDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrating_1_to_2_adds_the_slug_and_keeps_every_pending_record() {
        val id = "5f1c8a4e-0000-4000-8000-000000000001"

        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO attendance (
                    id, userId, checkpointId, checkpointCode, attendanceType, selfiePath,
                    capturedAt, latitude, longitude, accuracy, dutiesAcknowledged, dutiesVersionId,
                    deviceId, syncStatus, serverId, retryCount, claimedAt, nextAttemptAt, lastError,
                    createdAt, updatedAt
                ) VALUES (
                    '$id', 7, 1, 'CP-MAIN-GATE', 'TIME_IN', '/data/selfie.jpg',
                    1783663331000, 13.1775, 121.2803, 8.4, 1, 1,
                    NULL, 'PENDING', NULL, 0, NULL, NULL, NULL,
                    1783663331000, 1783663331000
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO checkpoints (id, code, name, description, latitude, longitude, status, updatedAt)
                VALUES (2, 'CLINIC', 'Clinic', NULL, 13.18, 121.28, 'ACTIVE', 1783663331000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        // The unsynced record — the thing that must never be lost — is still there, still PENDING.
        db.query("SELECT id, syncStatus, capturedAt FROM attendance").use { c ->
            assertTrue("the pending attendance survived the migration", c.moveToFirst())
            assertEquals(id, c.getString(0))
            assertEquals("PENDING", c.getString(1))
            assertEquals(1_783_663_331_000L, c.getLong(2))
            assertEquals("no duplicate rows", 1, c.count)
        }

        // The new column exists, and the row that predates it is simply null — resolution falls
        // back to the code, exactly as it did before, until the next refresh fills the slug in.
        db.query("SELECT code, slug FROM checkpoints").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("CLINIC", c.getString(0))
            assertTrue("slug is null on a pre-existing row", c.isNull(1))
        }
    }


    /**
     * Every migration, run in sequence over a database holding an unsynced record.
     *
     * The record is the point. This database is the only copy of an attendance between the shutter
     * and the server, and a migration that drops a table takes the evidence with it. Each new
     * migration is additive, but "is additive" is a claim, and this is what checks it.
     */
    @Test
    fun migrating_all_the_way_to_the_current_schema_keeps_the_pending_record() {
        val id = "5f1c8a4e-0000-4000-8000-000000000002"

        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO attendance (
                    id, userId, checkpointId, checkpointCode, attendanceType, selfiePath,
                    capturedAt, latitude, longitude, accuracy, dutiesAcknowledged, dutiesVersionId,
                    deviceId, syncStatus, serverId, retryCount, claimedAt, nextAttemptAt, lastError,
                    createdAt, updatedAt
                ) VALUES (
                    '$id', 7, 1, 'CP-MAIN-GATE', 'TIME_IN', '/data/selfie.jpg',
                    1783663331000, 13.1775, 121.2803, 8.4, 1, 1,
                    NULL, 'PENDING', NULL, 0, NULL, NULL, NULL,
                    1783663331000, 1783663331000
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO checkpoints (id, code, name, description, latitude, longitude, status, updatedAt)
                VALUES (2, 'CLINIC', 'Clinic', NULL, 13.18, 121.28, 'ACTIVE', 1783663331000)
                """.trimIndent()
            )
        }

        // The *current* version, not a number that was current once. Left behind at 5 while the
        // schema moved on, this test would keep passing while validating nothing about the last
        // two migrations.
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, *GUARD_MIGRATIONS)

        db.query("SELECT id, syncStatus FROM attendance").use { c ->
            assertTrue("the unsynced attendance survived every migration", c.moveToFirst())
            assertEquals(id, c.getString(0))
            assertEquals("PENDING", c.getString(1))
            assertEquals("no duplicates", 1, c.count)
        }

        db.query("SELECT code FROM checkpoints").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("CLINIC", c.getString(0))
        }

        // The tables the roster and the office notices live in, added by 2->3 and 3->4. A guard
        // reopening the app at a post with no signal reads both from here.
        db.query("SELECT COUNT(*) FROM schedule").use { c ->
            assertTrue("the schedule table exists", c.moveToFirst())
        }
        db.query("SELECT COUNT(*) FROM announcements").use { c ->
            assertTrue("the announcements table exists", c.moveToFirst())
        }

        // The post-shift self-evaluation, added by 4->5. The column on `attendance` is nullable, so
        // the pending record above — captured under the old duties-acknowledgement flow — migrates
        // untouched and still uploads.
        db.query("SELECT COUNT(*) FROM evaluation_questions").use { c ->
            assertTrue("the evaluation questions table exists", c.moveToFirst())
        }
        db.query("SELECT evaluationsJson FROM attendance").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("a record from before the evaluation existed carries none", c.isNull(0))
        }
    }

    /**
     * The evaluation question timing, added by 6->7.
     *
     * The backfill is the whole point. Every question cached before this column existed was asked
     * at the end of the shift, and the default has to say so — a question that migrated to
     * `TIME_IN` would start being put to guards as they arrived, asking them to report on a shift
     * they had not worked yet. Wrong at the wrong moment, and on the phone only, until the next
     * refresh happened to correct it.
     */
    @Test
    fun migrating_6_to_7_leaves_existing_questions_at_the_end_of_the_shift() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO evaluation_questions (id, question, sortOrder, updatedAt)
                VALUES (1, 'Was the logbook handed over properly?', 0, 1783663331000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, *GUARD_MIGRATIONS)

        db.query("SELECT question, timing FROM evaluation_questions").use { c ->
            assertTrue("the cached question survived", c.moveToFirst())
            assertEquals("Was the logbook handed over properly?", c.getString(0))
            assertEquals("TIME_OUT", c.getString(1))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}