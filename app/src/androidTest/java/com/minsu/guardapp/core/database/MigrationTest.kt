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

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
