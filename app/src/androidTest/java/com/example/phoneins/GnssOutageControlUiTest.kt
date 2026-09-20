package com.example.phoneins

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.phoneins.data.InsUiState
import com.example.phoneins.navigation.QualityFlag
import com.example.phoneins.service.NavigationService
import com.example.phoneins.ui.DashboardScreen
import com.example.phoneins.ui.PhoneInsTheme
import com.example.phoneins.ui.previewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GnssOutageControlUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun controlIsDisabledUntilServiceIsRunningAndInsIsInitialized() {
        composeRule.setContent {
            PhoneInsTheme { DashboardScreen(InsUiState()) }
        }

        composeRule.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("gnss_outage_toggle"))
        composeRule.onNodeWithText("GNSS aiding").assertIsDisplayed()
        composeRule.onNodeWithTag("gnss_outage_toggle")
            .assertIsNotEnabled()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
    }

    @Test
    fun initializedControlDispatchesRuntimeOutageAction() {
        var action: String? = null
        var extras: Map<String, Any> = emptyMap()
        composeRule.setContent {
            PhoneInsTheme {
                DashboardScreen(previewState()) { sentAction, sentExtras ->
                    action = sentAction
                    extras = sentExtras
                }
            }
        }

        composeRule.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("gnss_outage_toggle"))
        composeRule.onNodeWithContentDescription("GNSS aiding")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(NavigationService.ACTION_FORCE_GNSS_OUTAGE, action)
            assertTrue(extras["enabled"] == true)
        }
    }

    @Test
    fun activeControlUsesExplicitStatusAndOffAction() {
        var enabledExtra: Boolean? = null
        val activeState = previewState().copy(
            forcedGnssOutage = true,
            solution = previewState().solution.copy(
                qualityFlags = previewState().solution.qualityFlags or
                    QualityFlag.GNSS_AIDING_SUSPENDED or QualityFlag.DEAD_RECKONING,
            ),
        )
        composeRule.setContent {
            PhoneInsTheme {
                DashboardScreen(activeState) { _, extras -> enabledExtra = extras["enabled"] as? Boolean }
            }
        }

        composeRule.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("gnss_outage_toggle"))
        composeRule.onNodeWithText("GNSS aiding").assertIsDisplayed()
        composeRule.onNodeWithText("About outage testing").assertDoesNotExist()
        composeRule.onNodeWithText("Resume GNSS aiding").assertDoesNotExist()
        composeRule.onNodeWithTag("gnss_outage_toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
            .performClick()
        composeRule.runOnIdle { assertTrue(enabledExtra == false) }
    }

    @Test
    fun controlRemainsReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PhoneInsTheme { DashboardScreen(previewState()) }
            }
        }

        composeRule.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("gnss_outage_toggle"))
        composeRule.onNodeWithTag("gnss_outage_toggle")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
    }
}
