package com.minsu.guardapp.core.common

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Time source for attendance timestamps.
 *
 * Injected rather than called statically because attendance is captured offline and stamped
 * on-device, which makes the clock a correctness concern: tests must be able to fake it, and
 * the timestamp authority (device vs server) is still an open decision — see
 * `.docs/Implementation_Plan.md` §5.
 */
fun interface Clock {
    fun nowMillis(): Long
}

@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
