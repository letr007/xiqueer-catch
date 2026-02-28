package com.example.xiquercatch.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CaptureStateBus {
    private val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState

    fun update(block: (CaptureState) -> CaptureState) {
        mutableState.value = block(mutableState.value)
    }

    fun reset() {
        mutableState.value = CaptureState()
    }
}

data class CaptureState(
    val running: Boolean = false,
    val capturedCount: Int = 0,
    val lastError: String = "",
    val lastCaptureTime: Long = 0L
)
