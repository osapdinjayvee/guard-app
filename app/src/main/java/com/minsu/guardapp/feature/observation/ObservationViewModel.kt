package com.minsu.guardapp.feature.observation

import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.core.camera.ObservationPhotoCapture
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.core.location.LocationFix
import com.minsu.guardapp.core.location.LocationProvider
import com.minsu.guardapp.domain.model.AppSettings
import com.minsu.guardapp.domain.model.GpsFailurePolicy
import com.minsu.guardapp.domain.model.Observation
import com.minsu.guardapp.domain.model.ObservationDraft
import com.minsu.guardapp.domain.model.ObservationPhotoDraft
import com.minsu.guardapp.domain.model.ObservationRules
import com.minsu.guardapp.domain.repository.ObservationRepository
import com.minsu.guardapp.domain.repository.ProfileRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import javax.inject.Inject

/** Why the camera cannot take a photo yet, or null when it can. */
enum class ObservationGpsBlock { WAITING, NO_FIX, TOO_INACCURATE }

/** A photo taken for the observation being written, with the fix burned into it. */
data class CapturedPhoto(
    val file: File,
    val capturedAtMillis: Long,
    val fix: LocationFix,
)

data class ObservationUiState(
    val guardName: String = "",
    val settings: AppSettings = AppSettings(),
    val nowMillis: Long = 0,
    val fix: LocationFix? = null,
    val isAcquiringGps: Boolean = true,
    val note: String = "",
    val photos: List<CapturedPhoto> = emptyList(),
    val isCameraOpen: Boolean = false,
    val isCapturing: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val error: String? = null,
) {
    val canAddPhoto: Boolean
        get() = photos.size < ObservationRules.MAX_PHOTOS && !isSubmitting

    /**
     * Every photo needs coordinates — the server requires them — so no fix means no photo, whatever
     * the GPS failure policy says. The accuracy threshold, on the other hand, is the office's call and
     * applies only when the policy blocks, exactly as it does for attendance.
     */
    val gpsBlock: ObservationGpsBlock?
        get() = when {
            fix == null && isAcquiringGps -> ObservationGpsBlock.WAITING
            fix == null -> ObservationGpsBlock.NO_FIX
            settings.gpsFailurePolicy != GpsFailurePolicy.ALLOW &&
                fix.accuracyMetres > settings.gpsAccuracyThresholdMetres -> ObservationGpsBlock.TOO_INACCURATE
            else -> null
        }

    val canCapture: Boolean
        get() = isCameraOpen && !isCapturing && gpsBlock == null && photos.size < ObservationRules.MAX_PHOTOS

    val canSubmit: Boolean
        get() = note.isNotBlank() &&
            note.length <= ObservationRules.MAX_NOTE_LENGTH &&
            photos.size in ObservationRules.MIN_PHOTOS..ObservationRules.MAX_PHOTOS &&
            !isSubmitting && !submitted

    /** Burned into each photo, and shown live over the preview. Same source, both places. */
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
            add("OBSERVATION")
        }
}

@HiltViewModel
class ObservationViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val observations: ObservationRepository,
    private val location: LocationProvider,
    private val photoCapture: ObservationPhotoCapture,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ObservationUiState(nowMillis = clock.nowMillis()))
    val uiState: StateFlow<ObservationUiState> = _uiState.asStateFlow()

    /**
     * The idempotency key. One per screen: this ViewModel is scoped to the compose route, which is
     * popped once the report is saved, so the next observation gets a fresh instance and a fresh id.
     */
    private val observationId = UUID.randomUUID().toString()

    private var started = false

    /** Called once the camera and location permissions are granted; GPS before that would throw. */
    fun start() {
        if (started) return
        started = true

        viewModelScope.launch {
            val current = settings.current()
            val name = profiles.observe().first()?.name.orEmpty()
            _uiState.update { it.copy(settings = current, guardName = name) }
        }

        // The overlay's clock has to run: a time frozen when the screen opened would be burned into
        // a photo taken minutes later.
        viewModelScope.launch {
            while (true) {
                _uiState.update { it.copy(nowMillis = clock.nowMillis()) }
                delay(1_000)
            }
        }

        // A recent cached fix shows coordinates immediately; an old one is ignored, never stamped.
        viewModelScope.launch {
            location.lastKnownFix()?.let { seed ->
                if (clock.nowMillis() - seed.timeMillis in 0..FRESH_SEED_WINDOW_MILLIS) {
                    _uiState.update { if (it.fix == null) it.copy(fix = seed, isAcquiringGps = false) else it }
                }
            }
        }

        // Streamed, so the fix keeps sharpening while the guard walks from one shot to the next.
        viewModelScope.launch {
            location.stream()
                .catch { _uiState.update { it.copy(isAcquiringGps = false) } }
                .collect { fix -> _uiState.update { it.copy(fix = fix, isAcquiringGps = false) } }
        }

        viewModelScope.launch {
            delay(settings.current().gpsTimeoutSeconds * 1_000L)
            _uiState.update { if (it.fix == null) it.copy(isAcquiringGps = false) else it }
        }
    }

    fun onNoteChange(text: String) = _uiState.update {
        it.copy(note = text.take(ObservationRules.MAX_NOTE_LENGTH), error = null)
    }

    fun openCamera() {
        if (_uiState.value.canAddPhoto) _uiState.update { it.copy(isCameraOpen = true, error = null) }
    }

    fun closeCamera() = _uiState.update { it.copy(isCameraOpen = false, isCapturing = false) }

    /** The time and fix are snapshotted at the shutter, so the burned text matches what is stored. */
    fun capture(imageCapture: ImageCapture, executor: Executor) {
        val state = _uiState.value
        if (!state.canCapture) return
        val fix = state.fix ?: return
        val shutterAt = clock.nowMillis()

        _uiState.update { it.copy(isCapturing = true, error = null, nowMillis = shutterAt) }

        viewModelScope.launch {
            val snapshot = _uiState.value.copy(fix = fix, nowMillis = shutterAt)
            runCatching {
                photoCapture.capture(
                    imageCapture = imageCapture,
                    executor = executor,
                    name = "${observationId}_${UUID.randomUUID()}",
                    lines = snapshot.overlayLines,
                    maxDimension = snapshot.settings.imageMaxDimensionPx,
                    quality = snapshot.settings.imageQuality,
                )
            }.fold(
                onSuccess = { file ->
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            isCameraOpen = false,
                            photos = it.photos + CapturedPhoto(file, shutterAt, fix),
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isCapturing = false, error = error.message ?: "Capture failed.") }
                },
            )
        }
    }

    fun removePhoto(index: Int) {
        val photo = _uiState.value.photos.getOrNull(index) ?: return
        photo.file.delete()
        _uiState.update { it.copy(photos = it.photos - photo) }
    }

    /** Local-first: the report is durable the moment this succeeds, signal or not. */
    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        _uiState.update { it.copy(isSubmitting = true, error = null) }

        viewModelScope.launch {
            runCatching {
                observations.submit(
                    id = observationId,
                    draft = ObservationDraft(
                        note = state.note,
                        capturedAtMillis = clock.nowMillis(),
                        photos = state.photos.map { photo ->
                            ObservationPhotoDraft(
                                path = photo.file.absolutePath,
                                capturedAtMillis = photo.capturedAtMillis,
                                latitude = photo.fix.latitude,
                                longitude = photo.fix.longitude,
                                accuracyMetres = photo.fix.accuracyMetres,
                            )
                        },
                    ),
                )
            }.fold(
                onSuccess = { _uiState.update { it.copy(isSubmitting = false, submitted = true) } },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSubmitting = false, error = error.message ?: "Could not save the observation.")
                    }
                },
            )
        }
    }

    /**
     * A report abandoned before it was saved leaves no files behind. Once saved, the photos belong to
     * the queue and are the only copy until they upload, so they are left strictly alone.
     */
    override fun onCleared() {
        val state = _uiState.value
        if (!state.submitted) state.photos.forEach { it.file.delete() }
    }
}

/** The list behind the Home tile: what this guard has reported, and whether it has reached the office. */
@HiltViewModel
class ObservationsViewModel @Inject constructor(
    private val observations: ObservationRepository,
) : ViewModel() {

    val items: StateFlow<List<Observation>> = observations.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry(id: String) {
        viewModelScope.launch { observations.retry(id) }
    }
}

private const val FRESH_SEED_WINDOW_MILLIS = 60_000L

internal fun dateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy · h:mm:ss a", Locale.getDefault()).format(Date(millis))
