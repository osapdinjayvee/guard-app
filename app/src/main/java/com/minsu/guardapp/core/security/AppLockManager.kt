package com.minsu.guardapp.core.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locks the app when it leaves the foreground, so a guard who turned the lock on must
 * re-authenticate with biometrics or the device credential to return.
 */
interface AppLock {
    val locked: StateFlow<Boolean>
    fun unlock()
}

/**
 * The lock is only ever active when the guard has enabled it (opt-in). `locked` is the AND of
 * two facts: the preference is on, and the app is currently backgrounded.
 *
 * The app starts "backgrounded", so a cold start with the lock enabled lands on the unlock
 * screen; a password login clears it. ON_STOP fires on background, screen-off, and the app
 * switcher — the "idle or closed" trigger. [ProcessLifecycleOwner] reflects the whole app, not
 * one Activity's rotation.
 */
@Singleton
class AppLockManager @Inject constructor(
    lockPreferences: LockPreferences,
) : AppLock, DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backgrounded = MutableStateFlow(true)

    override val locked: StateFlow<Boolean> =
        combine(lockPreferences.enabled, backgrounded) { enabled, bg -> enabled && bg }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** After a successful biometric/credential check, and right after password login. */
    override fun unlock() {
        backgrounded.value = false
    }

    override fun onStop(owner: LifecycleOwner) {
        backgrounded.value = true
    }
}
