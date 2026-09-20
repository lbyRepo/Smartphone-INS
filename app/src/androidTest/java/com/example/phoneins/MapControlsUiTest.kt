package com.example.phoneins

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.phoneins.ui.*
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MapControlsUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun transparentZoomControlsShareFooterWithAttribution() = verify(1f)
    @Test fun zoomAndAttributionRemainReachableAtLargeFont() = verify(2f)

    private fun verify(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PhoneInsTheme {
                    PhoneInsScaffold(previewState(), Destination.DASHBOARD, {}, { _, _ -> }, {}, MapBasemap.TILES) {}
                }
            }
        }
        composeRule.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("position_map"))
        composeRule.onNodeWithTag("map_zoom_in").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val zoom = composeRule.onNodeWithTag("map_zoom_in").fetchSemanticsNode().boundsInRoot
        val canvas = composeRule.onNodeWithTag("position_map_canvas").fetchSemanticsNode().boundsInRoot
        assertTrue("Zoom control must sit below the map", zoom.top >= canvas.bottom)
        composeRule.onNodeWithTag("map_attribution").performScrollTo().assertIsDisplayed()
        val attribution = composeRule.onNodeWithTag("map_attribution").fetchSemanticsNode().boundsInRoot
        val out = composeRule.onNodeWithTag("map_zoom_out").fetchSemanticsNode().boundsInRoot
        assertTrue("Attribution must sit below the map", attribution.top >= canvas.bottom)
        assertTrue("Zoom controls must remain left of attribution", out.right <= attribution.left)
        assertTrue("Zoom controls and attribution must share the footer", out.top < attribution.bottom && attribution.top < out.bottom)
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "map-controls-${fontScale}.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        composeRule.onNodeWithContentDescription("Zoom in").performClick()
        composeRule.onNodeWithContentDescription("Zoom out").performClick()
        composeRule.onNodeWithTag("map_recenter").assertExists()
    }
}
