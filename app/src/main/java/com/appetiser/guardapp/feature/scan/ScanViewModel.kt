package com.appetiser.guardapp.feature.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appetiser.guardapp.domain.model.Checkpoint
import com.appetiser.guardapp.domain.model.CheckpointResolution
import com.appetiser.guardapp.domain.repository.CheckpointRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ScanState {
    data object Scanning : ScanState
    data class Resolved(val checkpoint: Checkpoint) : ScanState

    /** A retired checkpoint. Named apart from [Unknown] so the guard knows to try another door. */
    data class Disabled(val checkpoint: Checkpoint) : ScanState
    data class Unknown(val code: String) : ScanState
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val checkpoints: CheckpointRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Scanning)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /**
     * Resolution reads the local cache, so a guard at a basement checkpoint with no signal can
     * still scan. The server re-validates at sync time.
     */
    fun onCodeScanned(code: String) {
        if (_state.value != ScanState.Scanning) return

        viewModelScope.launch {
            _state.value = when (val resolution = checkpoints.resolve(code)) {
                is CheckpointResolution.Resolved -> ScanState.Resolved(resolution.checkpoint)
                is CheckpointResolution.Disabled -> ScanState.Disabled(resolution.checkpoint)
                is CheckpointResolution.Unknown -> ScanState.Unknown(resolution.code)
            }
        }
    }

    fun scanAgain() {
        _state.value = ScanState.Scanning
    }
}
