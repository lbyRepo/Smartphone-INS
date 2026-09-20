package com.example.phoneins.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.phoneins.data.InsUiState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.phoneins.navigation.QualityFlag
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

private const val PHONE_WIDTH_DP = 411
private const val PHONE_HEIGHT_DP = 914

/**
 * The complete visual acceptance matrix requested for each primary screen:
 * light/dark, portrait/landscape, and 100/130/200 percent font scales.
 */
@Preview(name = "light_portrait_100", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, fontScale = 1f, showBackground = true)
@Preview(name = "light_portrait_130", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, fontScale = 1.3f, showBackground = true)
@Preview(name = "light_portrait_200", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, fontScale = 2f, showBackground = true)
@Preview(name = "dark_portrait_100", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, fontScale = 1f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "dark_portrait_130", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "dark_portrait_200", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "light_landscape_100", widthDp = PHONE_HEIGHT_DP, heightDp = PHONE_WIDTH_DP, fontScale = 1f, showBackground = true)
@Preview(name = "light_landscape_130", widthDp = PHONE_HEIGHT_DP, heightDp = PHONE_WIDTH_DP, fontScale = 1.3f, showBackground = true)
@Preview(name = "light_landscape_200", widthDp = PHONE_HEIGHT_DP, heightDp = PHONE_WIDTH_DP, fontScale = 2f, showBackground = true)
@Preview(name = "dark_landscape_100", widthDp = PHONE_HEIGHT_DP, heightDp = PHONE_WIDTH_DP, fontScale = 1f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "dark_landscape_130", widthDp = PHONE_HEIGHT_DP, heightDp = PHONE_WIDTH_DP, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "dark_landscape_200", widthDp = PHONE_HEIGHT_DP, heightDp = PHONE_WIDTH_DP, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
private annotation class PrimaryScreenMatrix

@Composable
private fun PreviewApp(destination: Destination, outage: Boolean = false) {
    PhoneInsTheme {
        // Screenshot baselines stay on the deterministic grid renderer: street tiles would
        // introduce network-dependent pixels into the acceptance matrix.
        val base = previewState()
        val state = if (outage) base.copy(forcedGnssOutage = true, solution = base.solution.copy(
            qualityFlags = base.solution.qualityFlags or QualityFlag.GNSS_AIDING_SUSPENDED or QualityFlag.DEAD_RECKONING,
        )) else base
        val history = remember {
            PositionMapState().apply {
                repeat(30) { index ->
                    update(state.solution.copy(sequence = index.toLong(), monotonicNanos = (index + 1L) * 1_000_000_000L,
                        latitudeDeg = 1.3521 + index * 0.00001, longitudeDeg = 103.8198 + index * 0.000007,
                        latestGnss = state.solution.latestGnss?.copy(monotonicNanos = (index + 1L) * 1_000_000_000L,
                            latitudeDeg = 1.3521 + index * 0.000009, longitudeDeg = 103.8198 + index * 0.000006),
                    ))
                }
            }
        }
        PhoneInsScaffold(state, destination, {}, { _, _ -> }, {}, MapBasemap.GRID, mapStateOverride = history) {}
    }
}

@PreviewTest
@PrimaryScreenMatrix
@Composable
fun DashboardScreenshot() = PreviewApp(Destination.DASHBOARD)

@PreviewTest
@PrimaryScreenMatrix
@Composable
fun RawDataScreenshot() = PreviewApp(Destination.RAW)

@PreviewTest
@PrimaryScreenMatrix
@Composable
fun SetupScreenshot() = PreviewApp(Destination.SETUP)

@PreviewTest
@Preview(name = "outage_portrait", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, showBackground = true)
@Preview(name = "outage_large_font", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, fontScale = 2f, showBackground = true)
@Composable
fun DashboardOutageScreenshot() = PreviewApp(Destination.DASHBOARD, outage = true)

@PreviewTest
@Preview(name = "states_light", widthDp = 411, heightDp = 230, showBackground = true)
@Preview(name = "states_dark", widthDp = 411, heightDp = 230, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "states_light_200", widthDp = 411, heightDp = 350, fontScale = 2f, showBackground = true)
@Preview(name = "states_dark_200", widthDp = 411, heightDp = 350, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun InitializationIndicatorScreenshot() = PhoneInsTheme {
    Surface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val idle = InsUiState()
            DashboardStatus(idle)
            DashboardStatus(idle.copy(initializationInProgress = true))
            DashboardStatus(idle.copy(solution = idle.solution.copy(qualityFlags = QualityFlag.INITIALIZED)))
            DashboardStatus(idle.copy(initializationError = "Invalid pose"))
        }
    }
}
