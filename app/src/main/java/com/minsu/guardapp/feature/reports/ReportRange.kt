package com.minsu.guardapp.feature.reports

import com.minsu.guardapp.domain.model.AttendanceRecord
import com.minsu.guardapp.domain.model.AttendanceType
import java.util.Calendar
import java.util.TimeZone

enum class ReportRange { DAILY, WEEKLY, MONTHLY, CUSTOM }

/** A half-open window [fromMillis, toMillis) in the device's time zone. */
data class ReportWindow(val fromMillis: Long, val toMillis: Long)

data class ReportTotals(val timeIn: Int, val timeOut: Int) {
    val total: Int get() = timeIn + timeOut
}

/**
 * Turns a [ReportRange] into a concrete window, in local time.
 *
 * "Today" is midnight-to-midnight *local*, not UTC — a guard in UTC+8 filing at 01:00 expects
 * that entry in today's report, not yesterday's. [now] and [zone] are injected so this is a
 * pure function the tests can pin to a fixed instant.
 */
object ReportRanges {

    fun windowFor(
        range: ReportRange,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        customFrom: Long? = null,
        customTo: Long? = null,
    ): ReportWindow {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
        return when (range) {
            ReportRange.DAILY -> {
                val start = cal.startOfDay()
                ReportWindow(start, start + DAY)
            }
            ReportRange.WEEKLY -> {
                // Week containing `now`, starting on the locale's first day of week.
                val startOfDay = cal.startOfDay()
                val c = Calendar.getInstance(zone).apply { timeInMillis = startOfDay }
                val delta = c.get(Calendar.DAY_OF_WEEK) - c.firstDayOfWeek
                val offset = if (delta < 0) delta + 7 else delta
                val weekStart = startOfDay - offset * DAY
                ReportWindow(weekStart, weekStart + 7 * DAY)
            }
            ReportRange.MONTHLY -> {
                val c = Calendar.getInstance(zone).apply {
                    timeInMillis = now
                    set(Calendar.DAY_OF_MONTH, 1)
                }.startOfDayInPlace()
                val monthStart = c.timeInMillis
                c.add(Calendar.MONTH, 1)
                ReportWindow(monthStart, c.timeInMillis)
            }
            ReportRange.CUSTOM -> {
                // Inclusive of the whole `to` day: callers pass a day, we extend to its end.
                val from = customFrom ?: cal.startOfDay()
                val toDay = Calendar.getInstance(zone).apply {
                    timeInMillis = customTo ?: from
                }.startOfDayInPlace().timeInMillis
                ReportWindow(from, toDay + DAY)
            }
        }
    }

    fun totalsOf(records: List<AttendanceRecord>): ReportTotals = ReportTotals(
        timeIn = records.count { it.type == AttendanceType.TIME_IN },
        timeOut = records.count { it.type == AttendanceType.TIME_OUT },
    )

    private const val DAY = 24 * 60 * 60 * 1000L

    private fun Calendar.startOfDay(): Long {
        val c = clone() as Calendar
        return c.startOfDayInPlace().timeInMillis
    }

    private fun Calendar.startOfDayInPlace(): Calendar = apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
