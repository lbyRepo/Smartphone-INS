package com.example.phoneins.data

import com.example.phoneins.navigation.NavigationSolution
import com.example.phoneins.navigation.MagSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class InsUiState(
    val solution: NavigationSolution = NavigationSolution.empty(),
    val latestMag: MagSample? = null,
    val serviceRunning: Boolean = false,
    val simulated: Boolean = false,
    val simulatedMotionRunning: Boolean = false,
    val tcpConnected: Boolean = false,
    val aoaConnected: Boolean = false,
    val recording: Boolean = false,
    val replaying: Boolean = false,
    val forcedGnssOutage: Boolean = false,
    val outputRateHz: Double = 0.0,
    val imuRateHz: Double = 0.0,
    val accelerometerAgeMs: Double? = null,
    val gyroscopeAgeMs: Double? = null,
    val packetsSent: Long = 0,
    val droppedPackets: Long = 0,
    val statusMessage: String = "Stopped",
    val sessionNames: List<String> = emptyList(),
    val csvLoggingEnabled: Boolean = false,
    val csvLogElapsedMillis: Long = 0L,
    val csvLogFileName: String? = null,
    val csvLogPath: String? = null,
    val loggingError: String? = null,
    val droppedLogRecords: Long = 0,
    val initializationInProgress: Boolean = false,
    val initializationError: String? = null,
    val initializationProgress: Float = 0f,
    val initializationElapsedSeconds: Double = 0.0,
    val initializationDurationSeconds: Double = 15.0,
    val coarseRollDeg: Double = Double.NaN,
    val coarsePitchDeg: Double = Double.NaN,
    val recentTrialLogs: List<TrialLogSummary> = emptyList(),
    val masterConnected: Boolean = false,
    val masterRateHz: Double = 0.0,
    val masterMessage: String? = null,
    val csvReplay: CsvReplayUiState = CsvReplayUiState(),
    val trajectoryEpoch: Long = 0L,
)

enum class CsvReplayPhase { IDLE, INDEXING, READY, PLAYING, PAUSED, SEEKING, COMPLETED, ERROR }

data class TrialLogSummary(
    val id: String,
    val displayName: String,
    val modifiedMillis: Long,
    val durationMillis: Long,
    val rowCount: Int,
    val sizeBytes: Long,
)

data class CsvReplayUiState(
    val phase: CsvReplayPhase = CsvReplayPhase.IDLE,
    val selectedLogId: String? = null,
    val selectedLogName: String? = null,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val rowCount: Int = 0,
    val speed: Double = 1.0,
    val message: String? = null,
    val outputCsvName: String? = null,
    val outputCsvPath: String? = null,
)

object InsRepository {
    private val mutableState = MutableStateFlow(InsUiState())
    val state = mutableState.asStateFlow()

    fun update(transform: (InsUiState) -> InsUiState) = mutableState.update(transform)
    fun reset() { mutableState.value = InsUiState() }
}
