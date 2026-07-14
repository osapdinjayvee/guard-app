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
import com.minsu.guardapp.core.network.ApiResult
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.Duty
import com.minsu.guardapp.domain.model.EvaluationAnswer
import com.minsu.guardapp.domain.model.EvaluationQuestion
import com.minsu.guardapp.domain.model.GpsFailurePolicy
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.DutyRepository
import com.minsu.guardapp.domain.repository.EvaluationRepository
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
     * The post-shift self-evaluation, on a Time Out.
     *
     * Empty for a Time In or a checkpoint visit: an evaluation describes a shift that has *ended*,
     * and one attached to a Time In would be a claim about a shift that had not happened yet.
     */
    val questions: List<EvaluationQuestion> = emptyList(),
    /** Answers so far, keyed by question. One question is put at a time; this is what has been said. */
    val answers: Map<Long, Boolean> = emptyMap(),
    /**
     * The server said there are no questions. Told apart from "this phone has not downloaded them".
     *
     * Both look like an empty list and they mean opposite things. If the office has retired every
     * question, a guard must still be able to close their shift. If the phone simply never fetched
     * them, submitting would produce a Time Out the server rejects for carrying no answers. Guess
     * wrong one way and the guard is stranded; guess wrong the other and the evidence is lost.
     */
    val questionsKnownEmpty: Boolean = false,
    /** True once the photo is accepted and the guard has moved on to the questions. */
    val onEvaluationStep: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val error: String? = null,
) {
    /**
     * A Time Out is the only capture that carries an evaluation, because it is the only ending.
     *
     * Note what this does *not* say: `&& questions.isNotEmpty()`. It used to, and that was a hole.
     * A phone with no cached questions concluded that no evaluation was needed, submitted a Time Out
     * with none — and the server, which requires them on every Time Out, rejected it permanently.
     * The guard saw a shift they could not close and no reason why.
     *
     * A Time Out needs an evaluation whether or not this handset happens to have the questions. If
     * it does not have them, that is a problem to say out loud, not to answer by skipping the step.
     */
    val needsEvaluation: Boolean
        get() = type == AttendanceType.TIME_OUT

    /** A Time Out with nothing to ask, and no way to know whether that is real. Blocked, and said. */
    val missingQuestions: Boolean
        get() = needsEvaluation && questions.isEmpty() && !questionsKnownEmpty

    /** The question currently being put to the guard, or null when they have answered them all. */
    val currentQuestion: EvaluationQuestion?
        get() = questions.firstOrNull { it.id !in answers }

    val answeredCount: Int get() = questions.count { it.id in answers }

    /**
     * Every question, or none.
     *
     * A Time Out carrying four of seven answers is not a shorter evaluation — it is one where nobody
     * can tell whether the three missing answers were "no" or "the guard closed the app", and which
     * of those it was is exactly what the evaluation exists to find out. The server rejects a partial
     * one; so does this.
     */
    val evaluationComplete: Boolean
        get() = when {
            !needsEvaluation -> true
            // Genuinely nothing to ask: the office retired every question. Not a reason to leave a
            // guard unable to clock out of a shift they have finished.
            questions.isEmpty() -> questionsKnownEmpty
            else -> questions.all { it.id in answers }
        }

    val canSubmit: Boolean
        get() = capturedFile != null && evaluationComplete && !isSubmitting && !submitted
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
    private val evaluations: EvaluationRepository,
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
                    // Only a Time Out is asked. Read from the cache, so the questions are there at
                    // the end of a shift at a perimeter post with no signal.
                    questions = if (type == AttendanceType.TIME_OUT) evaluations.questions() else emptyList(),
                    nowMillis = clock.nowMillis(),
                )
            }
        }

        // An empty question cache on a Time Out is recoverable while there is still a signal, and
        // this is the last moment anyone is looking. Try once; if it fails, the guard is told plainly
        // rather than being walked into a submission the server will refuse.
        viewModelScope.launch {
            if (type != AttendanceType.TIME_OUT) return@launch
            if (evaluations.questions().isNotEmpty()) return@launch

            // A successful refresh that comes back empty is an *answer*: this campus asks nothing.
            // A failed one tells us only that we still do not know.
            val refreshed = evaluations.refresh()
            val questions = evaluations.questions()

            _uiState.update {
                it.copy(
                    questions = questions,
                    questionsKnownEmpty = refreshed is ApiResult.Success && questions.isEmpty(),
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

    /**
     * Answer the question currently on screen, and move to the next.
     *
     * Answers accumulate rather than being collected at the end, so a guard who is interrupted has
     * not lost the four they already gave.
     */
    fun answer(questionId: Long, answer: Boolean) = _uiState.update {
        it.copy(answers = it.answers + (questionId to answer))
    }

    /** Un-answer the last question, so a mis-tap is one tap to fix rather than a restart. */
    fun previousQuestion() = _uiState.update { state ->
        val lastAnswered = state.questions.lastOrNull { it.id in state.answers } ?: return@update state
        state.copy(answers = state.answers - lastAnswered.id)
    }

    /** The photo is accepted; on to the questions — or straight to submit, if there are none. */
    fun proceedToEvaluation() = _uiState.update { it.copy(onEvaluationStep = true) }

    /** Back to the photo, without discarding it or the answers already given. */
    fun backToPhoto() = _uiState.update { it.copy(onEvaluationStep = false) }

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
                        evaluations = state.questions.mapNotNull { question ->
                            state.answers[question.id]?.let { EvaluationAnswer(question.id, it) }
                        },
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
                onEvaluationStep = false,
                // The answers are kept. They describe the *shift*, not the photograph — a guard who
                // retakes a blurred selfie has not changed whether the logbook was completed, and
                // making them answer seven questions again to fix one bad frame is how you teach
                // them to accept a bad frame.
            )
        }
    }
}

private fun dateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy · h:mm:ss a", Locale.getDefault()).format(Date(millis))
