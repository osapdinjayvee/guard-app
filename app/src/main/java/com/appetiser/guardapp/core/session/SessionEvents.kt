package com.appetiser.guardapp.core.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot session signals, emitted from the network layer and observed by the UI.
 *
 * `extraBufferCapacity` keeps [notifySessionExpired] from suspending when nothing is
 * collecting — a background sync worker can hit a 401 with no UI on screen at all.
 */
@Singleton
class SessionEvents @Inject constructor() {

    private val _sessionExpired = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    fun notifySessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }
}
