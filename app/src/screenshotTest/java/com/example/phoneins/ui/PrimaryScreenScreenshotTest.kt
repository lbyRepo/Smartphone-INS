package com.example.phoneins.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
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
private fun PreviewApp(destination: Destination) {
    PhoneInsTheme {
        // Screenshot baselines stay on the deterministic grid renderer: street tiles would
        // introduce network-dependent pixels into the acceptance matrix.
        PhoneInsScaffold(previewState(), destination, {}, { _, _ -> }, {}, MapBasemap.GRID) {}
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
fun InitializationScreenshot() = PreviewApp(Destination.INITIALIZE)

@PreviewTest
@PrimaryScreenMatrix
@Composable
fun SessionsScreenshot() = PreviewApp(Destination.SETTINGS)
