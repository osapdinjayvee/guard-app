package com.appetiser.guardapp.core.database

import androidx.room.TypeConverter

/**
 * Lifecycle of a captured attendance record.
 *
 * ```
 *   insert            claim (CAS)              2xx
 *  ────────► PENDING ───────────► SYNCING ───────────► SYNCED   (terminal)
 *              ▲                     │
 *              │ reclaim             │ 5xx / IO      (retryCount++, nextAttemptAt set)
 *              │ (stale)             ▼
 *              └──────────────────  FAILED  ◄─── retriable
 *                                    │
 *                                    │ 4xx permanent
 *                                    ▼
 *                                 REJECTED  (terminal, surfaced to the guard)
 * ```
 *
 * [REJECTED] exists because the PRD's four states cannot express a server rejection. The
 * record was already committed locally and shown to the guard as done, so it must not be
 * deleted; and it must not sit in [FAILED], which means "retry" and would loop forever on a
 * permanently invalid record.
 *
 * [SYNCING] is a transient claim, not durable intent: a crash mid-upload strands rows there.
 * They are swept back to [PENDING], which is what makes "never lose a record" hold across
 * process death.
 */
enum class SyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    FAILED,
    REJECTED;

    companion object {
        /** Statuses a sync worker may claim. */
        val CLAIMABLE = listOf(PENDING, FAILED)

        /** Statuses that still owe the server a record, i.e. the Home screen's pending count. */
        val UNSYNCED = listOf(PENDING, SYNCING, FAILED)
    }
}

class SyncStatusConverter {
    @TypeConverter
    fun toStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    @TypeConverter
    fun fromStatus(status: SyncStatus): String = status.name
}
