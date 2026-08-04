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

/**
 * The patrol-only flag on a checkpoint.
 *
 * Defaults to 1 — true — for every row already on the phone. A checkpoint cached before this column
 * existed behaved as though shifts could start there, and that is exactly what it must keep doing:
 * defaulting to 0 would strand every guard whose cache predates the upgrade, unable to time in
 * anywhere, until a refresh happened to reach them.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE checkpoints ADD COLUMN allowsTimeInOut INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * Which end of a shift each evaluation question belongs to.
 *
 * Defaults to TIME_OUT, because that is where every cached question was asked before the column
 * existed. Any other default would move the office's existing questions to the start of the shift
 * on upgrade — silently, on the phone only, until the next refresh corrected it.
 *
 * The default is not merely for the backfill: it is what a row inserted by a *previous* build would
 * carry if one ever ran against this schema, and NOT NULL without it would fail the insert.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE evaluation_questions ADD COLUMN timing TEXT NOT NULL DEFAULT 'TIME_OUT'"
        )
    }
}

val GUARD_MIGRATIONS = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
)
