package com.minsu.guardapp.feature.selfie

import com.minsu.guardapp.core.location.LocationFix
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.GpsFailurePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SelfieUiStateTest {

    private val gateA = Checkpoint(1, "GATE-A", "Main Gate", isActive = true, latitude = null, longitude = null)

    private fun state(
        fix: LocationFix? = null,
        settings: AppSettings = AppSettings(),
    ) = SelfieUiState(
        guardName = "Juan Dela Cruz",
        checkpoint = gateA,
        type = AttendanceType.TIME_IN,
        fix = fix,
        settings = settings,
        nowMillis = CAPTURED_AT,
    )

    private fun fix(accuracy: Float) =
        LocationFix(14.599512, 120.984222, accuracy, timeMillis = 0)

    /** The default policy is BLOCK, so a missing fix stops capture rather than recording nothing. */
    @Test
    fun `without a fix the guard cannot capture under the default policy`() {
        val current = state(fix = null)

        assertEquals(GpsBlock.NO_FIX, current.gpsBlock)
        assertFalse(current.canCapture)
    }

    @Test
    fun `a fix worse than the threshold blocks capture`() {
        val current = state(fix = fix(accuracy = 120f), settings = AppSettings(gpsAccuracyThresholdMetres = 50f))

        assertEquals(GpsBlock.TOO_INACCURATE, current.gpsBlock)
        assertFalse(current.canCapture)
    }

    @Test
    fun `a fix within the threshold allows capture`() {
        val current = state(fix = fix(accuracy = 8f), settings = AppSettings(gpsAccuracyThresholdMetres = 50f))

        assertNull(current.gpsBlock)
        assertTrue(current.canCapture)
    }

    /** The server can relax the rule; the client must honour it rather than hardcode BLOCK. */
    @Test
    fun `the ALLOW policy permits capture with no fix at all`() {
        val current = state(fix = null, settings = AppSettings(gpsFailurePolicy = GpsFailurePolicy.ALLOW))

        assertNull(current.gpsBlock)
        assertTrue(current.canCapture)
    }

    @Test
    fun `the overlay names every field the PRD requires`() {
        val lines = state(fix = fix(8.4f)).overlayLines

        assertTrue(lines.any { it.contains("Juan Dela Cruz") })
        // The formatted year is derived, not hardcoded: this test must not depend on the
        // machine's timezone straddling a year boundary.
        val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(CAPTURED_AT))
        assertTrue("date and time", lines.any { it.contains(year) && it.contains(":") })
        assertTrue("latitude", lines.any { it.contains("Lat 14.599512") })
        assertTrue("longitude", lines.any { it.contains("Lng 120.984222") })
        assertTrue("accuracy", lines.any { it.contains("±8 m") })
        assertTrue("checkpoint", lines.any { it.contains("Main Gate (GATE-A)") })
        assertTrue("attendance type", lines.any { it == "TIME IN" })
    }

    @Test
    fun `a missing fix is stated plainly in the overlay rather than left blank`() {
        val lines = state(fix = null).overlayLines

        assertTrue(lines.any { it == "Location unavailable" })
    }

    @Test
    fun `capture is blocked while another capture is in flight`() {
        val current = state(fix = fix(8f)).copy(isCapturing = true)

        assertFalse(current.canCapture)
    }

    // --- Duties acknowledgement gate (PRD §6) ---

    private fun capturedState() = state(fix = fix(8f)).copy(
        capturedFile = java.io.File("/tmp/selfie.jpg"),
    )

    @Test
    fun `submission is blocked until the duties checkbox is confirmed`() {
        val unacknowledged = capturedState().copy(dutiesAcknowledged = false)

        assertFalse(unacknowledged.canSubmit)
    }

    @Test
    fun `an acknowledged capture can be submitted`() {
        val acknowledged = capturedState().copy(dutiesAcknowledged = true)

        assertTrue(acknowledged.canSubmit)
    }

    @Test
    fun `there is nothing to submit before a photo is captured`() {
        val noPhoto = state(fix = fix(8f)).copy(capturedFile = null, dutiesAcknowledged = true)

        assertFalse(noPhoto.canSubmit)
    }

    @Test
    fun `a submitted record cannot be submitted again`() {
        val alreadyDone = capturedState().copy(dutiesAcknowledged = true, submitted = true)

        assertFalse(alreadyDone.canSubmit)
    }

    private companion object {
        /** 2026-07-10T06:02:11Z. */
        const val CAPTURED_AT = 1_783_663_331_000L
    }
}
