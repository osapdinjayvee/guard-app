package com.minsu.guardapp.ui.format

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A roster time as the guard reads it: `23:00:00` → `11:00 PM`.
 *
 * The server keeps shift times as 24-hour wall clock, which is the right thing for it to store and
 * the wrong thing to put in front of a guard on a night shift. Falls back to the raw value rather
 * than showing nothing, so an unexpected format degrades to something still readable.
 */
fun shiftTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val hhmm = raw.take(5)
    return runCatching {
        val parsed = SimpleDateFormat("HH:mm", Locale.US).parse(hhmm)!!
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(parsed)
    }.getOrDefault(hhmm)
}
