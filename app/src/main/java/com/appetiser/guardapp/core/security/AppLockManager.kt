package com.appetiser.guardapp.core.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locks the app when it leaves the foreground, so a signed-in guard must re-authenticate with
 * biometrics or the device credential (PIN/pattern/password) to return.
 */
interface AppLock {
    val locked: StateFlow<Boolean>
    fun unlock()
}

/**
 * Starts locked: a cold start with an existing session lands on the lock, not the content. The
 * lock only reaches the screen when a session is also present (see the auth gate), so a
 * logged-out app shows login, never the lock.
 *
 * ON_STOP fires when the app is backgrounded, the screen turns off, or the recents/app switcher
 * covers it — the "idle or closed" trigger. Uses [ProcessLifecycleOwner] so it reflects the
 * whole app, not one Activity's rotation.
 */
@Singleton
class AppLockManager @Inject constructor() : AppLock, DefaultLifecycleObserver {

    private val _locked = MutableStateFlow(true)
    override val locked: StateFlow<Boolean> = _locked.asStateFlow()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** Called after a successful biometric/credential check, and right after password login. */
    override fun unlock() {
        _locked.value = false
    }

    /** The app went to background: require re-authentication before it is shown again. */
    override fun onStop(owner: LifecycleOwner) {
        _locked.value = true
    }
}
