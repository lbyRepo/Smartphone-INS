package com.example.phoneins.ui

import com.example.phoneins.data.InsUiState
import com.example.phoneins.data.InitializationStatus
import com.example.phoneins.data.initializationStatus
import com.example.phoneins.navigation.QualityFlag
import org.junit.Assert.assertEquals
import org.junit.Test

class InitializationStatusTest {
    @Test fun initializationStatesAndRetry() {
        val idle = InsUiState()
        val aligning = idle.copy(initializationInProgress = true)
        val failed = aligning.copy(initializationInProgress = false, initializationError = "Invalid pose")
        val retry = failed.copy(initializationError = null, initializationInProgress = true)
        val complete = retry.copy(initializationInProgress = false,
            solution = idle.solution.copy(qualityFlags = QualityFlag.INITIALIZED))
        assertEquals(InitializationStatus.IDLE, idle.initializationStatus)
        assertEquals(InitializationStatus.IN_PROGRESS, aligning.initializationStatus)
        assertEquals(InitializationStatus.FAILED, failed.initializationStatus)
        assertEquals(InitializationStatus.IN_PROGRESS, retry.initializationStatus)
        assertEquals(InitializationStatus.COMPLETE, complete.initializationStatus)
        assertEquals(InitializationStatus.COMPLETE, complete.copy(forcedGnssOutage = true,
            loggingError = "Disk full").initializationStatus)
        assertEquals(InitializationStatus.IN_PROGRESS, complete.copy(initializationInProgress = true).initializationStatus)
    }
}
