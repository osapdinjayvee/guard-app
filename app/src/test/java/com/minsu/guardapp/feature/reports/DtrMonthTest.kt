package com.minsu.guardapp.feature.reports

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The dates the two document endpoints are asked for.
 *
 * Both are strings the server parses, and both are wrong in ways nothing else would catch: a DTR
 * for the wrong month looks like a perfectly good DTR, and a report whose range is off by a day
 * quietly loses a shift off the end of a fortnight.
 */
class DtrMonthTest {

    private fun at(iso: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(iso)!!.time

    /** The range Reports has selected, in the form `guard-report` takes. */
    @Test
    fun `a window is asked for as plain dates`() {
        assertEquals("2026-08-01", at("2026-08-01 00:00").asApiDate())
        assertEquals("2026-08-15", at("2026-08-15 23:59").asApiDate())
    }

    /**
     * The end of a range is the last instant of its day, not midnight at the start of the next.
     *
     * Formatting that instant must land on the day the guard chose. An off-by-one here asks the
     * server for a range ending the previous evening, and the last shift of a fortnight disappears
     * from the report the office signs.
     */
    @Test
    fun `the last instant of a day is still that day`() {
        assertEquals("2026-08-31", (at("2026-09-01 00:00") - 1).asApiDate())
    }

    /** What the guard reads back on the report screen. */
    @Test
    fun `dates are shown the way a guard would say them`() {
        assertEquals("1 Aug 2026", "2026-08-01".asReadableDate())
    }

    /** A date the server sends in a shape this build does not expect is shown, not swallowed. */
    @Test
    fun `an unparseable date is left as it came`() {
        assertEquals("not-a-date", "not-a-date".asReadableDate())
    }
}
