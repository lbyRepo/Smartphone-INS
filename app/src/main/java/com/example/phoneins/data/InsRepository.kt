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
    val tcpConnected: Boolean = false,
    val aoaConnected: Boolean = false,
    val recording: Boolean = false,
    val replaying: Boolean = false,
    val outputRateHz: Double = 0.0,
    val imuRateHz: Double = 0.0,
    val packetsSent: Long = 0,
    val droppedPackets: Long = 0,
    val statusMessage: String = "Stopped",
    val sessionNames: List<String> = emptyList(),
)

object InsRepository {
    private val mutableState = MutableStateFlow(InsUiState())
    val state = mutableState.asStateFlow()

    fun update(transform: (InsUiState) -> InsUiState) = mutableState.update(transform)
    fun reset() { mutableState.value = InsUiState() }
}
