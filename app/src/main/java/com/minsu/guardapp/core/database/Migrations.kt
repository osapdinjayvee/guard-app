package com.minsu.guardapp.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations.
 *
 * This database holds captured attendance that has not yet reached the server, so it is never
 * dropped and re-created — `fallbackToDestructiveMigration` would silently destroy the exact
 * evidence the offline-first design exists to protect. Every schema change earns a migration.
 */

/**
 * Adds `checkpoints.slug`.
 *
 * A scanned QR is resolved against the code *or* the slug, because stickers printed before the QR
 * payload was settled carry the slug, and they are already on walls. Nullable and unbackfilled: the
 * next checkpoint refresh fills it in from the server, and until then resolution falls back to the
 * code, which is what it matched on before.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE checkpoints ADD COLUMN slug TEXT")
    }
}

/**
 * Adds the cached duty roster.
 *
 * The scanner has to know whether the guard is stationed or roving before it can offer them the
 * right buttons, and it has to know that in a basement. Additive: no existing row is touched, so the
 * unsynced attendance this database exists to protect is not at risk.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `schedule` (
                `date` TEXT NOT NULL,
                `dutyType` TEXT NOT NULL,
                `dutyName` TEXT,
                `startsAt` TEXT,
                `endsAt` TEXT,
                `totalHours` REAL NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`date`)
            )
            """.trimIndent()
        )
    }
}

/**
 * Persists announcements.
 *
 * They were held in memory, so an announcement survived for as long as the process did and no
 * longer. A guard who reopened the app at their post, offline, saw nothing — while the profile,
 * checkpoints, duties and roster were all still there. Additive; no existing row is touched.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `announcements` (
                `id` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }
}

/**
 * Adds the post-shift self-evaluation: the questions, and the answers held on a record awaiting
 * upload.
 *
 * Additive. The `evaluationsJson` column is nullable, so every attendance already sitting in the
 * queue — captured under the old duties-acknowledgement flow — migrates untouched and still uploads.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `evaluation_questions` (
                `id` INTEGER NOT NULL,
                `question` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("ALTER TABLE attendance ADD COLUMN evaluationsJson TEXT")
    }
}

val GUARD_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
