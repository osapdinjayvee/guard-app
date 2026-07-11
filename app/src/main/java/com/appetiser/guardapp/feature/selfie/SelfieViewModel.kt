package com.appetiser.guardapp.feature.selfie

import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appetiser.guardapp.core.camera.SelfieCapture
import com.appetiser.guardapp.core.common.Clock
import com.appetiser.guardapp.core.location.LocationFix
import com.appetiser.guardapp.core.location.LocationProvider
import com.appetiser.guardapp.domain.model.AppSettings
import com.appetiser.guardapp.domain.model.AttendanceDraft
import com.appetiser.guardapp.domain.model.AttendanceType
import com.appetiser.guardapp.domain.model.Checkpoint
import com.appetiser.guardapp.domain.model.Duty
import com.appetiser.guardapp.domain.model.GpsFailurePolicy
import com.appetiser.guardapp.domain.repository.AttendanceRepository
import com.appetiser.guardapp.domain.repository.DutyRepository
import com.appetiser.guardapp.domain.repository.ProfileRepository
import com.appetiser.guardapp.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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

/** Why the guard cannot capture yet, or null when they can. */
enum class GpsBlock { WAITING, NO_FIX, TOO_INACCURATE }

data class SelfieUiState(
    val guardName: String = "",
    val checkpoint: Checkpoint? = null,
    val type: AttendanceType? = null,
    val fix: LocationFix? = null,
    val settings: AppSettings = AppSettings(),
    val nowMillis: Long = 0,
    val isCapturing: Boolean = false,
    val capturedFile: File? = null,
    val duty: Duty? = null,
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
            } else {
                add("Location unavailable")
            }
            add(checkpoint?.let { "${it.name} (${it.code})" } ?: "—")
            add(type?.name?.replace('_', ' ') ?: "—")
        }

    val gpsBlock: GpsBlock?
        get() = when {
            settings.gpsFailurePolicy == GpsFailurePolicy.ALLOW -> null
            fix == null -> GpsBlock.NO_FIX
            fix.accuracyMetres > settings.gpsAccuracyThresholdMetres -> GpsBlock.TOO_INACCURATE
            else -> null
        }

    val canCapture: Boolean
        get() = checkpoint != null && type != null && !isCapturing && gpsBlock == null
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

            val fix = location.currentFix(timeoutMillis = current.gpsTimeoutSeconds * 1_000L)
            _uiState.update { it.copy(fix = fix, nowMillis = clock.nowMillis()) }
        }
    }

    fun setDutiesAcknowledged(acknowledged: Boolean) =
        _uiState.update { it.copy(dutiesAcknowledged = acknowledged) }

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
        _uiState.update { it.copy(capturedFile = null, error = null) }
    }
}

private fun dateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm:ss", Locale.getDefault()).format(Date(millis))
