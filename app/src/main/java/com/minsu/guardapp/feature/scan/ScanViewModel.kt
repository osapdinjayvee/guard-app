package com.minsu.guardapp.feature.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minsu.guardapp.domain.model.AttendanceType
import com.minsu.guardapp.domain.model.Checkpoint
import com.minsu.guardapp.domain.model.CheckpointResolution
import com.minsu.guardapp.domain.repository.CheckpointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ScanState {
    data object Scanning : ScanState

    /** Resolved and active: the guard now picks Time In or Time Out. */
    data class ChoosingType(val checkpoint: Checkpoint) : ScanState

    /** Checkpoint and type settled; the selfie step follows in T-19. */
    data class ReadyToCapture(val checkpoint: Checkpoint, val type: AttendanceType) : ScanState

    /** A retired checkpoint. Named apart from [Unknown] so the guard knows to try another door. */
    data class Disabled(val checkpoint: Checkpoint) : ScanState

    /** The server confirmed there is no such checkpoint. */
    data class Unknown(val code: String) : ScanState

    /** Not cached and the server could not be asked. Never worded as if the code were invalid. */
    data class Unverifiable(val code: String) : ScanState

    /** A resolution is in flight — the cache missed and we are asking the server. */
    data object Resolving : ScanState
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val checkpoints: CheckpointRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Scanning)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /**
     * Resolution reads the local cache first, so a guard at a basement checkpoint with no signal
     * still scans instantly. Only a cache miss reaches for the network, and even then the record is
     * re-validated by the server at sync time.
     */
    fun onCodeScanned(code: String) {
        if (_state.value != ScanState.Scanning) return

        viewModelScope.launch {
            // A cache hit settles this in microseconds and never shows. It is the miss — the round
            // trip to the server — that would otherwise leave the camera looking frozen.
            _state.value = ScanState.Resolving

            _state.value = when (val resolution = checkpoints.resolve(code)) {
                is CheckpointResolution.Resolved -> ScanState.ChoosingType(resolution.checkpoint)
                is CheckpointResolution.Disabled -> ScanState.Disabled(resolution.checkpoint)
                is CheckpointResolution.Unknown -> ScanState.Unknown(resolution.code)
                is CheckpointResolution.Unverifiable -> ScanState.Unverifiable(resolution.code)
            }
        }
    }

    /** Only reachable from [ScanState.ChoosingType]: a type without a checkpoint is meaningless. */
    fun onTypeChosen(type: AttendanceType) {
        val current = _state.value as? ScanState.ChoosingType ?: return
        _state.value = ScanState.ReadyToCapture(current.checkpoint, type)
    }

    fun scanAgain() {
        _state.value = ScanState.Scanning
    }
}
