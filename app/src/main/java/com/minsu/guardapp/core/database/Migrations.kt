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

val GUARD_MIGRATIONS = arrayOf(MIGRATION_1_2)
