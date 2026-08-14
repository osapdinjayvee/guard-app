package com.minsu.guardapp.feature.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.core.common.Clock
import com.minsu.guardapp.domain.model.DutyType
import com.minsu.guardapp.domain.model.roundPosts
import com.minsu.guardapp.domain.repository.AttendanceRepository
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.ScheduleRepository
import com.minsu.guardapp.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed interface ScanState {
    data object Scanning : ScanState

    /** A resolution is in flight — the cache missed and we are asking the server. */
    data object Resolving : ScanState

    /**
     * Resolved, active, and allowed. The guard now picks a type.
     *
     * [allowedTypes] comes from the duty roster: a stationed guard is offered Time In and Time Out
     * alone, and a roving guard is also offered a Checkpoint visit. A type the roster rules forbid
     * *right now* is removed rather than shown and refused — a button that exists only to reject you
     * is a trap — and [notice] says why it is missing, because a guard who is simply shown fewer
     * buttons has been told nothing.
     */
    data class ChoosingType(
        val checkpoint: Checkpoint,
        val allowedTypes: List<AttendanceType>,
        val notice: String? = null,
    ) : ScanState

    /** Checkpoint and type settled; the selfie step follows. */
    data class ReadyToCapture(val checkpoint: Checkpoint, val type: AttendanceType) : ScanState

    /** A retired checkpoint. Named apart from [Unknown] so the guard knows to try another door. */
    data class Disabled(val checkpoint: Checkpoint) : ScanState

    /** The server confirmed there is no such checkpoint. */
    data class Unknown(val code: String) : ScanState

    /** Not cached and the server could not be asked. Never worded as if the code were invalid. */
    data class Unverifiable(val code: String) : ScanState

    /**
     * A stationed guard, at a checkpoint that is not the one they timed in at.
     *
     * Their post is not assigned by anyone — it is wherever they scanned in. What they may not do is
     * close the shift somewhere else.
     */
    data class WrongPost(val scanned: Checkpoint, val timedInAt: String) : ScanState

    /**
     * The shift is already closed — the guard has timed out today. Scanning offers nothing more;
     * Time In returns with the next shift. Distinct from a rest day: they worked today, and finished.
     */
    data class ShiftComplete(val checkpoint: Checkpoint) : ScanState

    /** Rostered, but not today. A rest day is not an error, and is not worded as one. */
    data object NotScheduledToday : ScanState

    /** Nobody has joined this login to a guard on the roster. An admin problem, said plainly. */
    data object NotOnRoster : ScanState
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val checkpoints: CheckpointRepository,
    private val schedule: ScheduleRepository,
    private val attendance: AttendanceRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Scanning)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /** Today's duty, for the banner above the viewfinder. */
    private val _duty = MutableStateFlow<DutyAssignment?>(null)
    val duty: StateFlow<DutyAssignment?> = _duty.asStateFlow()

    init {
        viewModelScope.launch { _duty.value = schedule.today() }
    }

    /**
     * Resolution reads the local cache first, so a guard at a basement checkpoint with no signal
     * still scans instantly. Only a cache miss reaches for the network.
     *
     * The roster rules are then applied *here*, before the camera ever opens. The server enforces
     * them again on submission — it is the authority — but a guard who learns they were at the wrong
     * post from a rejection that lands after the shift has ended has already wasted the capture, and
     * cannot do anything about it. Told now, they can walk to the right door.
     */
    fun onCodeScanned(code: String) {
        if (_state.value != ScanState.Scanning) return

        viewModelScope.launch {
            // A cache hit settles this in microseconds and never shows. It is the miss — the round
            // trip to the server — that would otherwise leave the camera looking frozen.
            _state.value = ScanState.Resolving

            val resolution = checkpoints.resolve(code)

            _state.value = when (resolution) {
                is CheckpointResolution.Disabled -> ScanState.Disabled(resolution.checkpoint)
                is CheckpointResolution.Unknown -> ScanState.Unknown(resolution.code)
                is CheckpointResolution.Unverifiable -> ScanState.Unverifiable(resolution.code)
                is CheckpointResolution.Resolved -> rosterVerdict(resolution.checkpoint)
            }
        }
    }

    /** What the duty roster says about scanning *this* checkpoint, right now. */
    private suspend fun rosterVerdict(checkpoint: Checkpoint): ScanState {
        if (!schedule.isLinked.first()) return ScanState.NotOnRoster

        val duty = schedule.today() ?: return ScanState.NotScheduledToday
        _duty.value = duty

        // The shift is already closed. A guard who has timed out is done for the day — offering Time
        // Out again (or anything else) would let them re-open a finished shift. Checked before the
        // wrong-post rule, because "your shift is over" is truer and kinder than "wrong post" to a
        // guard who has clocked out and is scanning on their way past.
        if (attendance.hasTimedOutToday()) return ScanState.ShiftComplete(checkpoint)

        // A stationed guard's post is wherever they timed in. If they have not timed in yet, this
        // scan *is* the post — anything they scan is allowed, and it becomes the one they must
        // return to.
        val post = schedule.postTimedInAtToday()

        if (duty.dutyType == DutyType.STATIONED &&
            post != null &&
            post != checkpoint.id
        ) {
            val timedInAt = checkpoints.byId(post)?.code ?: "another checkpoint"
            return ScanState.WrongPost(scanned = checkpoint, timedInAt = timedInAt)
        }

        return withTimeRules(checkpoint, duty)
    }

    /**
     * The two rules that depend on *when*, rather than *where*.
     *
     * Both are checked here, before the camera opens, and again by the server on submission. The
     * server is the authority; this is the kindness. A guard who learns their Time In was too early
     * from a rejection that lands after the shift has ended has already taken the selfie, walked
     * away, and can do nothing about it.
     */
    private suspend fun withTimeRules(checkpoint: Checkpoint, duty: DutyAssignment): ScanState {
        val config = settings.current()
        val now = clock.nowMillis()

        var types = duty.allowedTypes
        val notices = mutableListOf<String>()

        // Not every post is a place a shift begins or ends. A perimeter marker or a stairwell door
        // is somewhere a roving guard passes on a round; nobody clocks on at one.
        if (!checkpoint.allowsTimeInOut) {
            types = types - AttendanceType.TIME_IN - AttendanceType.TIME_OUT
            notices += "${checkpoint.code} is a patrol checkpoint — shifts do not start or end here."
        }

        // A shift cannot be opened long before it starts. Turning up an hour early and timing in
        // does not make the shift an hour longer. Lateness is not capped — the late timestamp is
        // itself the evidence, and refusing it would leave the shift with no record at all.
        val opensAt = shiftOpensAt(duty, config.timeInEarlyMinutes)

        if (opensAt != null && now < opensAt) {
            types = types - AttendanceType.TIME_IN
            notices += "Time In opens at ${clockTime(opensAt)}, " +
                "${config.timeInEarlyMinutes} minutes before your shift."
        }

        // One Time In per shift. Once the guard has clocked on today, offering Time In again would
        // let them open a second shift on top of the first — the "multiple time ins" a re-scan or a
        // fumbled tap produces. It is removed rather than shown and refused, so what is left is Time
        // Out (and, for a rover, checkpoint visits) — the only things that can still happen today.
        val timedInToday = schedule.postTimedInAtToday() != null
        if (timedInToday) {
            types = types - AttendanceType.TIME_IN
        }

        // A patrol starts when the shift does. A checkpoint visit before any Time In would be
        // evidence of a round walked by someone who, on paper, had not clocked on.
        //
        // Said only to a guard who patrols. A stationed guard was never offered a checkpoint visit
        // in the first place, so telling them "a patrol starts when your shift does" explains the
        // absence of a button they have never had, over the two buttons they do have — which reads
        // as a warning about those, and is why a stationed guard asked whether Time Out was broken.
        if (!timedInToday) {
            types = types - AttendanceType.CHECKPOINT
            if (duty.dutyType == DutyType.ROVING) {
                notices += "Time In first — a patrol starts when your shift does."
            }
        }

        // A patrol is movement. Scanning the same door twice in succession is a guard standing
        // still, and it must not be a way to satisfy the round without walking it.
        if (attendance.lastVisitedCheckpointToday() == checkpoint.id) {
            types = types - AttendanceType.CHECKPOINT
            notices += "You have just visited ${checkpoint.code}. Patrol another post before you " +
                "scan this one again."
        }

        // The round is every post, twice — and an unfinished one is now told, not enforced.
        //
        // Time Out used to be withheld until every post had its visits. The block landed on the
        // wrong person: a guard pulled off patrol, sent to an incident, or working a short shift
        // has done nothing wrong, and withholding the button produced a shift with no closing
        // record at all — the office loses the evidence of when they went home, and the guard
        // loses the proof they worked it. A missing record is worse evidence than a short one.
        //
        // So the shortfall is still counted, still named, and still shown; it just no longer
        // refuses. The server agrees: it stopped rejecting these too, and the two must not drift
        // apart or the app offers a Time Out the server then throws away.
        if (duty.dutyType == DutyType.ROVING && config.minVisitsPerCheckpoint > 0) {
            val required = config.minVisitsPerCheckpoint
            val visits = attendance.checkpointVisitsToday()
            val posts = checkpoints.observeActive().first().roundPosts()
            val outstanding = posts.filter { (visits[it.id] ?: 0) < required }

            if (posts.isNotEmpty() && outstanding.isNotEmpty()) {
                // Names the posts still owed, not just a count. A guard told "4 of 6" at the end of
                // an eight-hour shift has to work out which two they missed; a guard told "CLINIC,
                // LIBRARY" can simply walk there.
                val names = outstanding.take(3).joinToString(", ") { it.code }
                val more = outstanding.size - minOf(3, outstanding.size)

                notices += "Still to do: $names" +
                    (if (more > 0) " and $more more." else ".") +
                    " You can still time out."
            }
        }

        // Every rule can fire at once, and then there is nothing left to offer.
        //
        // It happens at the worst moment: a rover standing at the last post they still owe, having
        // just scanned it, at the end of the shift. Time In is spent, Checkpoint is blocked because
        // they have only just been here, and Time Out is blocked because this post is short. The
        // card came out with no buttons at all — a guard trying to go home, reading two paragraphs
        // about what they cannot do, with nothing to tap.
        //
        // The way out is always the same, so it is said plainly rather than left to be deduced.
        if (types.isEmpty()) {
            notices += "Visit another post first, then come back here."
        }

        return ScanState.ChoosingType(
            checkpoint = checkpoint,
            allowedTypes = types,
            notice = notices.joinToString(" ").ifBlank { null },
        )
    }

    /**
     * When the guard may first time in: the shift's start, less the grace window.
     *
     * Null when the roster gives no start time, which means there is nothing to be early for and the
     * rule cannot be applied — better to let the guard record their attendance than to block them on
     * a roster the office left half-filled.
     */
    private fun shiftOpensAt(duty: DutyAssignment, earlyMinutes: Int): Long? {
        val startsAt = duty.startsAt ?: return null

        return runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .parse("${duty.date} ${startsAt.padTime()}")!!
            parsed.time - earlyMinutes * 60_000L
        }.getOrNull()
    }

    /** `23:00` and `23:00:00` both arrive from the roster; only one of them parses. */
    private fun String.padTime(): String = if (length == 5) "$this:00" else this

    private fun clockTime(millis: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

    /** Only reachable from [ScanState.ChoosingType]: a type without a checkpoint is meaningless. */
    fun onTypeChosen(type: AttendanceType) {
        val current = _state.value as? ScanState.ChoosingType ?: return
        if (type !in current.allowedTypes) return
        _state.value = ScanState.ReadyToCapture(current.checkpoint, type)
    }

    fun scanAgain() {
        _state.value = ScanState.Scanning
    }
}
