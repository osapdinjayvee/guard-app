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
        // Default: the acquisition window has closed. A test about "no fix" means "we looked and
        // found nothing", not "we have not looked yet" — those are different states now.
        isAcquiringGps: Boolean = false,
        checkpoint: Checkpoint = gateA,
    ) = SelfieUiState(
        guardName = "Juan Dela Cruz",
        checkpoint = checkpoint,
        type = AttendanceType.TIME_IN,
        fix = fix,
        isAcquiringGps = isAcquiringGps,
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

    /**
     * "Still looking" is not "there is nothing there".
     *
     * A cold GPS receiver takes seconds. Announcing "location unavailable" the instant the camera
     * opens is false, and it is how a guard learns to disbelieve the message on the occasion it is
     * true.
     */
    @Test
    fun `while the fix is still being acquired the guard is told so, not that it failed`() {
        val current = state(fix = null, isAcquiringGps = true)

        assertEquals(GpsBlock.WAITING, current.gpsBlock)
        assertFalse(current.canCapture)
        assertTrue(current.overlayLines.any { it == "Acquiring GPS…" })
        assertFalse("must not claim failure while still trying",
            current.overlayLines.any { it == "Location unavailable" })
    }

    // --- The geofence, enforced before the shutter rather than by the server hours later ---

    private val clinic = Checkpoint(
        2, "CLINIC", "Clinic", isActive = true,
        latitude = 13.18982241, longitude = 121.19418619,
    )

    /**
     * The real rejection this exists to prevent: a capture taken 8.8 km from the checkpoint, which
     * the server refused long after the guard had walked away. The phone knows the distance the
     * moment the fix lands, so it says so then and disables the shutter.
     */
    @Test
    fun `a fix outside the geofence blocks capture and names the distance`() {
        val eightKmAway = LocationFix(13.1725719, 121.2739113, accuracyMetres = 8f, timeMillis = 0)
        val current = state(
            fix = eightKmAway,
            settings = AppSettings(geofenceRadiusMetres = 100f),
            checkpoint = clinic,
        )

        assertEquals(GpsBlock.OUT_OF_RANGE, current.gpsBlock)
        assertFalse("the shutter must be disabled", current.canCapture)

        val distance = current.distanceToCheckpointMetres!!
        assertTrue("distance is ~8.8 km, was $distance", distance in 8_700f..9_000f)
    }

    @Test
    fun `a fix inside the geofence allows capture`() {
        val atTheClinic = LocationFix(13.18985, 121.19420, accuracyMetres = 8f, timeMillis = 0)
        val current = state(
            fix = atTheClinic,
            settings = AppSettings(geofenceRadiusMetres = 100f),
            checkpoint = clinic,
        )

        assertNull(current.gpsBlock)
        assertTrue(current.canCapture)
    }

    /** A checkpoint an admin never placed on the map cannot be fenced, and must not block anyone. */
    @Test
    fun `a checkpoint without coordinates is never out of range`() {
        val current = state(fix = fix(8f), settings = AppSettings(geofenceRadiusMetres = 100f))

        assertNull(current.distanceToCheckpointMetres)
        assertNull(current.gpsBlock)
        assertTrue(current.canCapture)
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
