package com.minsu.guardapp.feature.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.model.DutyAssignment
import com.minsu.guardapp.domain.repository.CheckpointRepository
import com.minsu.guardapp.domain.repository.ScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ScanState {
    data object Scanning : ScanState

    /** A resolution is in flight — the cache missed and we are asking the server. */
    data object Resolving : ScanState

    /**
     * Resolved, active, and allowed. The guard now picks a type.
     *
     * [allowedTypes] comes from the duty roster: a stationed guard is offered Time In and Time Out
     * alone, and a roving guard is also offered a Checkpoint visit.
     */
    data class ChoosingType(
        val checkpoint: Checkpoint,
        val allowedTypes: List<AttendanceType>,
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

    /** Rostered, but not today. A rest day is not an error, and is not worded as one. */
    data object NotScheduledToday : ScanState

    /** Nobody has joined this login to a guard on the roster. An admin problem, said plainly. */
    data object NotOnRoster : ScanState
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val checkpoints: CheckpointRepository,
    private val schedule: ScheduleRepository,
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

        // A stationed guard's post is wherever they timed in. If they have not timed in yet, this
        // scan *is* the post — anything they scan is allowed, and it becomes the one they must
        // return to.
        val post = schedule.postTimedInAtToday()

        if (duty.dutyType == com.minsu.guardapp.domain.model.DutyType.STATIONED &&
            post != null &&
            post != checkpoint.id
        ) {
            val timedInAt = checkpoints.byId(post)?.code ?: "another checkpoint"
            return ScanState.WrongPost(scanned = checkpoint, timedInAt = timedInAt)
        }

        return ScanState.ChoosingType(checkpoint, duty.allowedTypes)
    }

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
