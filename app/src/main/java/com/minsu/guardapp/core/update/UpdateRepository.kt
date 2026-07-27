package com.minsu.guardapp.core.update

import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.di.InstalledVersionCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a check the guard asked for by hand, where a failure is worth saying out loud. */
sealed interface CheckOutcome {
    data class Finished(val status: UpdateStatus) : CheckOutcome
    data object Unreachable : CheckOutcome
    /** Throttled: a recent automatic check already answered this. */
    data object Skipped : CheckOutcome
}

interface UpdateRepository {
    val status: StateFlow<UpdateStatus>

    /** True while a check is in flight, so the Account row can say "Checking…". */
    val isChecking: StateFlow<Boolean>

    /**
     * @param force run even if the throttle window has not elapsed. The manual path forces;
     *   the automatic one does not.
     */
    suspend fun check(force: Boolean = false): CheckOutcome

    /** Stop offering this version until a newer one appears. Does not apply to a forced update. */
    suspend fun dismiss(update: AppUpdate)
}

@Singleton
class DefaultUpdateRepository @Inject constructor(
    private val source: UpdateManifestSource,
    private val preferences: UpdatePreferences,
    private val clock: Clock,
    @InstalledVersionCode private val installedVersionCode: Int,
) : UpdateRepository {

    private val state = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    override val status: StateFlow<UpdateStatus> = state.asStateFlow()

    private val checking = MutableStateFlow(false)
    override val isChecking: StateFlow<Boolean> = checking.asStateFlow()

    // Two screens can ask at once — the gate on launch and the Account row under a guard's
    // thumb. Serialised so they cannot both fetch, and so the second sees the first's answer.
    private val lock = Mutex()

    override suspend fun check(force: Boolean): CheckOutcome = lock.withLock {
        val now = clock.nowMillis()
        val since = now - preferences.lastCheckedAt()
        // A clock that moved backwards (manual change, timezone database update) would otherwise
        // suppress every check until real time caught up.
        val throttled = since in 0 until THROTTLE_MILLIS

        if (!force && throttled && state.value != UpdateStatus.Unknown) {
            return@withLock CheckOutcome.Skipped
        }

        checking.value = true
        val result = try {
            source.fetch()
        } finally {
            checking.value = false
        }

        val manifest = result.getOrElse {
            // Deliberately leaves `state` untouched. A failed check knows nothing — it does not
            // know the app is up to date, and saying so would be a lie a guard might act on.
            return@withLock CheckOutcome.Unreachable
        }

        preferences.markChecked(now)
        // A guard who taps "Check for updates" is owed the truth, even about a version they
        // declined earlier. Reporting "you're on the latest version" because of a past dismissal
        // would be a lie told in answer to a direct question — and it would leave them no way
        // back to an update they have changed their mind about.
        val dismissed = if (force) 0 else preferences.dismissedVersionCode.first()
        val resolved = resolve(manifest, dismissed)
        state.value = resolved
        CheckOutcome.Finished(resolved)
    }

    override suspend fun dismiss(update: AppUpdate) {
        preferences.dismiss(update.versionCode)
        // Re-resolve rather than blanking: a dismissal must not clear a *required* update, and
        // the guard is entitled to find the offer again on the Account screen.
        if (state.value is UpdateStatus.Available) {
            state.value = UpdateStatus.UpToDate
        }
    }

    /**
     * Compares on versionCode alone.
     *
     * Never on versionName: it is a string a human chose, and "0.10.0" sorts before "0.9.0".
     * versionCode is the one number Android itself guarantees is monotonic.
     */
    private fun resolve(manifest: UpdateManifestDto, dismissed: Int): UpdateStatus {
        val update = manifest.toAppUpdate()

        // Checked before the newer-version test, and independently of it. A floor above the
        // installed build means this build is broken against the server whether or not the guard
        // has already declined the download.
        if (installedVersionCode < manifest.minSupportedVersionCode) {
            return UpdateStatus.Required(update)
        }

        return when {
            manifest.versionCode <= installedVersionCode -> UpdateStatus.UpToDate
            manifest.versionCode <= dismissed -> UpdateStatus.UpToDate
            else -> UpdateStatus.Available(update)
        }
    }

    private companion object {
        /**
         * Six hours. A guard reopens this app dozens of times a shift; checking on each one would
         * spend their data to learn the same answer. Long enough to be invisible, short enough
         * that a release published in the morning reaches the night shift.
         */
        val THROTTLE_MILLIS = TimeUnit.HOURS.toMillis(6)
    }
}
