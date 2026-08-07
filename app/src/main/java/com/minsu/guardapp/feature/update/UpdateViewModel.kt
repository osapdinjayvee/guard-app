package com.minsu.guardapp.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.di.InstalledVersionCode
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
    /** Every build this handset could install, newest first. Empty when only one is on offer. */
    val versions: List<AppUpdate> = emptyList(),
    /** The one the button will install. Defaults to the newest; the guard may choose another. */
    val selected: AppUpdate? = null,
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
    @InstalledVersionCode private val installedVersionCode: Int,
) : ViewModel() {

    private val permissionNeeded = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    /** One-shot text for the Account screen's snackbar. The gate does not use it. */
    val snackbar: StateFlow<String?> = message

    /**
     * Which build the guard picked, or null for "whatever is newest".
     *
     * Held as a version code rather than the object, so a refreshed list does not leave a stale
     * copy of a release selected — the choice survives, the data behind it is always current.
     */
    private val chosen = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<UpdateUiState> = combine(
        repository.status,
        downloader.progress,
        repository.isChecking,
        permissionNeeded,
        combine(repository.available, chosen) { versions, code -> versions to code },
    ) { status, download, checking, needsPermission, (versions, code) ->
        val newest = when (val s = status) {
            is UpdateStatus.Available -> s.update
            is UpdateStatus.Required -> s.update
            else -> null
        }

        UpdateUiState(
            status = status,
            download = download,
            isChecking = checking,
            versions = versions,
            // A chosen code that is no longer on offer falls back to the newest rather than to
            // nothing: the office withdrawing a release must not leave the button inert.
            selected = versions.firstOrNull { it.versionCode == code } ?: newest,
            needsInstallPermission = needsPermission,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateUiState())

    /** Choose a build other than the newest. */
    fun select(update: AppUpdate) {
        if (uiState.value.selected?.versionCode == update.versionCode) return

        // A half-finished download of the version being replaced is worth nothing and would be
        // mistaken for this one's the moment Install was tapped.
        downloader.cancel()
        chosen.value = update.versionCode
    }

    /** The quiet path, run on launch. Says nothing when it fails. */
    fun checkQuietly() = viewModelScope.launch {
        // Launch is the only moment an already-installed APK can be recognised as spent: the
        // install replaces this process, so nothing gets to clean up on the way out. Done before
        // the check, and independently of whether it succeeds — reclaiming tens of megabytes of
        // the guard's storage should not wait on the update server being reachable.
        downloader.pruneInstalled(installedVersionCode)

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
        // The chosen build, not simply the newest — the guard may have picked another from the
        // list. Falls back to the newest when nothing was chosen, which is the common case.
        val update = uiState.value.selected ?: uiState.value.available ?: return
        permissionNeeded.value = false

        when (val current = downloader.progress.value) {
            // Only if it is the build being asked for. A Ready file from a previously selected
            // version would otherwise install the wrong release, silently and successfully.
            is DownloadState.Ready if current.versionCode == update.versionCode ->
                installOrRequestPermission(current)
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
