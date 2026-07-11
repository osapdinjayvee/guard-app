package com.minsu.guardapp.feature.selfie

import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.camera.SelfieCapture
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.location.LocationFix
import com.minsu.guardapp.core.location.LocationProvider
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.AttendanceDraft
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.domain.model.GpsFailurePolicy
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.DutyRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Why the guard cannot capture yet, or null when they can. */
enum class GpsBlock { WAITING, NO_FIX, TOO_INACCURATE, OUT_OF_RANGE }

data class SelfieUiState(
    val guardName: String = "",
    val checkpoint: Checkpoint? = null,
    val type: AttendanceType? = null,
    val fix: LocationFix? = null,
    /** True until the first fix lands, or until the acquisition window runs out without one. */
    val isAcquiringGps: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val nowMillis: Long = 0,
    val isCapturing: Boolean = false,
    val capturedFile: File? = null,
    val duty: Duty? = null,
    /**
     * True once the guard has accepted the photo and moved on to the duties.
     *
     * The acknowledgement is its own screen, not a checkbox tucked under the preview. It is a
     * statement about what the guard is responsible for on this shift, and it has to be read; a
     * tick-box competing for attention with a photograph of your own face is not read.
     */
    val onDutiesStep: Boolean = false,
    val dutiesAcknowledged: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val error: String? = null,
) {
    /** Submission is blocked until the guard confirms the duties checkbox (PRD §6). */
    val canSubmit: Boolean
        get() = capturedFile != null && dutiesAcknowledged && !isSubmitting && !submitted
    /** The lines burned into the image, and shown live over the preview. Same source, both places. */
    val overlayLines: List<String>
        get() = buildList {
            add(guardName.ifBlank { "—" })
            add(dateTime(nowMillis))
            if (fix != null) {
                add("Lat ${"%.6f".format(fix.latitude)}  Lng ${"%.6f".format(fix.longitude)}")
                add("Accuracy ±${"%.0f".format(fix.accuracyMetres)} m")
            } else if (isAcquiringGps) {
                add("Acquiring GPS…")
            } else {
                add("Location unavailable")
            }
            add(checkpoint?.let { "${it.name} (${it.code})" } ?: "—")
            add(type?.name?.replace('_', ' ') ?: "—")
        }

    /**
     * How far the guard is from the checkpoint they scanned, or null when it cannot be known —
     * no fix yet, or a checkpoint an admin never gave coordinates to.
     */
    val distanceToCheckpointMetres: Float?
        get() {
            val fix = fix ?: return null
            val lat = checkpoint?.latitude ?: return null
            val lng = checkpoint?.longitude ?: return null
            return haversineMetres(fix.latitude, fix.longitude, lat, lng)
        }

    val gpsBlock: GpsBlock?
        get() = when {
            settings.gpsFailurePolicy == GpsFailurePolicy.ALLOW -> null
            // Still looking. Distinct from NO_FIX: a cold GPS chip takes seconds, and telling a
            // guard "location unavailable" the instant the screen opens — before the receiver has
            // had a chance — is both wrong and the reason they stop trusting the message when it
            // is real.
            fix == null && isAcquiringGps -> GpsBlock.WAITING
            fix == null -> GpsBlock.NO_FIX
            fix.accuracyMetres > settings.gpsAccuracyThresholdMetres -> GpsBlock.TOO_INACCURATE
            // The same geofence the server enforces, enforced here first.
            //
            // Without this the guard takes a selfie, acknowledges the duties, submits — and the
            // record is refused hours later, once it reaches a server that can see they were eight
            // kilometres away. The capture is wasted, the rejection arrives with no explanation
            // they can act on at the time, and the app looks broken. The information needed to say
            // "you are too far from this checkpoint" is already on the phone the moment the fix
            // lands, so it is said then.
            outOfRange -> GpsBlock.OUT_OF_RANGE
            else -> null
        }

    private val outOfRange: Boolean
        get() {
            val radius = settings.geofenceRadiusMetres
            if (radius <= 0f) return false
            val distance = distanceToCheckpointMetres ?: return false
            return distance > radius
        }

    val canCapture: Boolean
        get() = checkpoint != null && type != null && !isCapturing && gpsBlock == null
}

/** Great-circle distance in metres. Mirrors the server's check, so the two agree. */
private fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return (earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))).toFloat()
}

@HiltViewModel
class SelfieViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val duties: DutyRepository,
    private val attendance: AttendanceRepository,
    private val location: LocationProvider,
    private val selfieCapture: SelfieCapture,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SelfieUiState())
    val uiState: StateFlow<SelfieUiState> = _uiState.asStateFlow()

    /** Generated once, before any capture, and reused as the record's idempotency key. */
    private val recordId = UUID.randomUUID().toString()

    fun start(checkpoint: Checkpoint, type: AttendanceType) {
        if (_uiState.value.checkpoint != null) return

        _uiState.update {
            it.copy(checkpoint = checkpoint, type = type, nowMillis = clock.nowMillis())
        }

        viewModelScope.launch {
            val current = settings.current()
            _uiState.update {
                it.copy(
                    guardName = profiles.observe().first()?.name.orEmpty(),
                    settings = current,
                    duty = duties.activeDuty(),
                    nowMillis = clock.nowMillis(),
                )
            }
        }

        // The overlay is *live*, so the clock in it has to actually run. A timestamp frozen at the
        // moment the screen opened would be burned into a photo taken a minute later.
        viewModelScope.launch {
            while (true) {
                _uiState.update { it.copy(nowMillis = clock.nowMillis()) }
                delay(1_000)
            }
        }

        // A stream, not a single fix. The first reading off a cold receiver is routinely hundreds
        // of metres out and tightens over the next few seconds; a one-shot attempt would burn that
        // first bad reading into the photo, or — if it timed out — leave the guard permanently
        // unable to capture with no way to retry. This keeps improving for as long as the screen
        // is open, and stops the moment it closes.
        viewModelScope.launch {
            location.stream().collect { fix ->
                _uiState.update { it.copy(fix = fix, isAcquiringGps = false) }
            }
        }

        // The acquisition window is not a deadline for the *stream* — it keeps trying — but it is
        // the point at which we stop saying "acquiring" and admit we have nothing.
        viewModelScope.launch {
            delay(settings.current().gpsTimeoutSeconds * 1_000L)
            _uiState.update { if (it.fix == null) it.copy(isAcquiringGps = false) else it }
        }
    }

    fun setDutiesAcknowledged(acknowledged: Boolean) =
        _uiState.update { it.copy(dutiesAcknowledged = acknowledged) }

    /** The photo is accepted; on to the duties. */
    fun proceedToDuties() = _uiState.update { it.copy(onDutiesStep = true) }

    /** Back to the photo, without discarding it. */
    fun backToPhoto() = _uiState.update { it.copy(onDutiesStep = false) }

    /**
     * The commit. Writes the record to Room and enqueues a sync — local-first, so the
     * attendance is durable the instant this succeeds, with or without a network.
     */
    fun submit() {
        val state = _uiState.value
        val file = state.capturedFile
        val checkpoint = state.checkpoint
        val type = state.type
        if (!state.canSubmit || file == null || checkpoint == null || type == null) return

        _uiState.update { it.copy(isSubmitting = true, error = null) }

        viewModelScope.launch {
            runCatching {
                attendance.submit(
                    id = recordId,
                    draft = AttendanceDraft(
                        checkpointId = checkpoint.id,
                        checkpointCode = checkpoint.code,
                        type = type,
                        selfiePath = file.absolutePath,
                        capturedAtMillis = state.nowMillis,
                        latitude = state.fix?.latitude,
                        longitude = state.fix?.longitude,
                        accuracyMetres = state.fix?.accuracyMetres,
                        dutiesVersionId = state.duty?.id,
                    ),
                )
            }.fold(
                onSuccess = { _uiState.update { it.copy(isSubmitting = false, submitted = true) } },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSubmitting = false, error = error.message ?: "Could not save the record.")
                    }
                },
            )
        }
    }

    /**
     * The metadata is snapshotted at the shutter, not read from whatever the preview last
     * showed. A fix that arrived while the guard was framing the shot would otherwise disagree
     * with the burned-in text.
     */
    fun capture(imageCapture: ImageCapture, executor: Executor) {
        val state = _uiState.value
        if (!state.canCapture) return

        _uiState.update { it.copy(isCapturing = true, error = null, nowMillis = clock.nowMillis()) }

        viewModelScope.launch {
            val snapshot = _uiState.value
            runCatching {
                selfieCapture.capture(
                    imageCapture = imageCapture,
                    executor = executor,
                    id = recordId,
                    lines = snapshot.overlayLines,
                    maxDimension = snapshot.settings.imageMaxDimensionPx,
                    quality = snapshot.settings.imageQuality,
                )
            }.fold(
                onSuccess = { file -> _uiState.update { it.copy(isCapturing = false, capturedFile = file) } },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isCapturing = false, error = error.message ?: "Capture failed.")
                    }
                },
            )
        }
    }

    /** Discards the image so the guard can retake before anything is committed. */
    fun retake() {
        _uiState.value.capturedFile?.delete()
        _uiState.update {
            it.copy(
                capturedFile = null,
                error = null,
                onDutiesStep = false,
                // A retake means the earlier acknowledgement referred to a photo that no longer
                // exists. Make them tick it again rather than carrying consent forward silently.
                dutiesAcknowledged = false,
            )
        }
    }
}

private fun dateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm:ss", Locale.getDefault()).format(Date(millis))
