package com.minsu.guardapp.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface SyncScheduler {
    /** Requests an upload of any pending attendance. Safe to call after every submission. */
    fun requestSync()

    /** A drain the guard asked for. Unlike [requestSync], this one always runs. */
    fun syncNow()

    /** True while a drain is waiting on its constraints or actually uploading. */
    fun observeSyncing(): Flow<Boolean>
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {

    private val workManager get() = WorkManager.getInstance(context)

    /**
     * One unique worker, not expedited. On minSdk 24 expedited work degrades to a foreground
     * service with a notification and a quota; a constrained one-time request runs promptly
     * enough and needs neither. A burst of submissions collapses to a single drain via KEEP.
     */
    override fun requestSync() = enqueue(ExistingWorkPolicy.KEEP)

    /**
     * REPLACE, not KEEP.
     *
     * KEEP is right for the automatic path: a dozen submissions should collapse into one drain.
     * It is wrong here. A guard taps "Sync now" precisely when something looks stuck, and at that
     * moment there is very often already a drain sitting in the queue, waiting out a backoff or a
     * network constraint. Under KEEP the tap would be discarded in favour of that stale request —
     * the button would visibly do nothing, at the one moment the guard is watching it.
     */
    override fun syncNow() = enqueue(ExistingWorkPolicy.REPLACE)

    override fun observeSyncing(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).map { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }

    private fun enqueue(policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "attendance-sync"
    }
}
