package com.appetiser.guardapp.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface SyncScheduler {
    /** Requests an upload of any pending attendance. Safe to call after every submission. */
    fun requestSync()
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {

    /**
     * One unique worker, not expedited. On minSdk 24 expedited work degrades to a foreground
     * service with a notification and a quota; a constrained one-time request runs promptly
     * enough and needs neither. A burst of submissions collapses to a single drain via KEEP.
     */
    override fun requestSync() {
        val request = OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "attendance-sync"
    }
}