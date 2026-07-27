package com.minsu.guardapp.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.update.ApkDownloader
import com.minsu.guardapp.core.update.ApkInstaller
import com.minsu.guardapp.core.update.AppUpdate
import com.minsu.guardapp.core.update.CheckOutcome
import com.minsu.guardapp.core.update.DownloadState
import com.minsu.guardapp.core.update.UpdateRepository
import com.minsu.guardapp.core.update.UpdateStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.Unknown,
    val download: DownloadState = DownloadState.Idle,
    val isChecking: Boolean = false,
    /**
     * The APK is downloaded, but Android has not been told this app may install packages. The
     * guard has to grant that on a system screen — there is no way to do it from here.
     */
    val needsInstallPermission: Boolean = false,
) {
    val available: AppUpdate?
        get() = when (val s = status) {
            is UpdateStatus.Available -> s.update
            is UpdateStatus.Required -> s.update
            else -> null
        }

    val isRequired: Boolean get() = status is UpdateStatus.Required
    val isBusy: Boolean get() = download is DownloadState.Downloading
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
) : ViewModel() {

    private val permissionNeeded = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    /** One-shot text for the Account screen's snackbar. The gate does not use it. */
    val snackbar: StateFlow<String?> = message

    val uiState: StateFlow<UpdateUiState> = combine(
        repository.status,
        downloader.progress,
        repository.isChecking,
        permissionNeeded,
    ) { status, download, checking, needsPermission ->
        UpdateUiState(
            status = status,
            download = download,
            isChecking = checking,
            needsInstallPermission = needsPermission,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateUiState())

    /** The quiet path, run on launch. Says nothing when it fails. */
    fun checkQuietly() = viewModelScope.launch {
        repository.check(force = false)
    }

    /** The Account screen's "Check for updates" row. This one reports what happened. */
    fun checkNow() = viewModelScope.launch {
        message.value = when (val outcome = repository.check(force = true)) {
            CheckOutcome.Unreachable ->
                "Couldn't reach the update server. Check your connection and try again."
            CheckOutcome.Skipped -> null
            is CheckOutcome.Finished -> when (outcome.status) {
                is UpdateStatus.UpToDate -> "You're on the latest version."
                is UpdateStatus.Unknown -> null
                is UpdateStatus.Available -> null // The sheet says it better than a snackbar can.
                is UpdateStatus.Required -> null
            }
        }
    }

    /**
     * Download, then install.
     *
     * The permission is checked *after* the download rather than before: granting it sends the
     * guard out to a system settings screen, and making them do that before they have any idea
     * how long the download takes is worse than asking once the file is ready.
     */
    fun startUpdate() {
        val update = uiState.value.available ?: return
        permissionNeeded.value = false

        when (val current = downloader.progress.value) {
            is DownloadState.Ready -> installOrRequestPermission(current)
            is DownloadState.Downloading -> Unit
            else -> {
                // start() publishes Downloading before it returns, so the wait below cannot be
                // satisfied by a stale result from an earlier attempt.
                downloader.start(update)
                viewModelScope.launch {
                    val settled = downloader.progress.first {
                        it is DownloadState.Ready || it is DownloadState.Failed
                    }
                    if (settled is DownloadState.Ready) installOrRequestPermission(settled)
                }
            }
        }
    }

    private fun installOrRequestPermission(ready: DownloadState.Ready) {
        if (installer.canInstall()) {
            permissionNeeded.value = false
            installer.install(ready.apk)
        } else {
            permissionNeeded.value = true
            installer.requestInstallPermission()
        }
    }

    fun retryDownload() {
        downloader.reset()
        startUpdate()
    }

    fun dismiss() = viewModelScope.launch {
        val update = uiState.value.available ?: return@launch
        // Declining is also a decision to stop downloading — leaving it running would spend a
        // guard's data on a file they just said they did not want.
        downloader.cancel()
        repository.dismiss(update)
    }

    fun snackbarShown() = message.update { null }
}
