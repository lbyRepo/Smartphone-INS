package com.example.phoneins.data

enum class InitializationStatus(val label: String, val icon: String) {
    IDLE("Not initialized", "○"),
    IN_PROGRESS("Initializing", "◷"),
    COMPLETE("Initialized", "✓"),
    FAILED("Initialization failed", "!"),
}

val InsUiState.initializationStatus: InitializationStatus
    get() = when {
        initializationError != null -> InitializationStatus.FAILED
        initializationInProgress -> InitializationStatus.IN_PROGRESS
        solution.initialized -> InitializationStatus.COMPLETE
        else -> InitializationStatus.IDLE
    }
