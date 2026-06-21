package com.example.phoneins

import android.Manifest
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.phoneins.data.InsRepository
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneInsUiTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun resetRepository() = InsRepository.reset()

    @Test
    fun allPrimaryDestinationsAreReachable() {
        composeRule.onNodeWithTag("dashboard").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_raw").performClick()
        composeRule.onNodeWithTag("raw_data").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_initialize").performClick()
        composeRule.onNodeWithTag("initialization").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("sessions").assertIsDisplayed()
    }

    @Test
    fun navigationHasSemanticsAndMinimumTouchTargets() {
        composeRule.onNodeWithContentDescription("Raw tab").assertIsDisplayed()
        composeRule.onNodeWithTag("nav_raw").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("service_toggle").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun manualInitializationRejectsInvalidCoordinates() {
        composeRule.onNodeWithTag("nav_initialize").performClick()
        composeRule.onNodeWithTag("init_manual_mode").performClick()
        composeRule.onNodeWithTag("manual_latitude").performTextReplacement("91")
        composeRule.onNodeWithTag("manual_error").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("initialize_manual").assertIsNotEnabled()
    }

    @Test
    fun sessionsContentCanScrollToOutputDiagnostics() {
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithTag("output_links").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun serviceBadgeReflectsRepositoryState() {
        composeRule.runOnIdle {
            InsRepository.update { it.copy(serviceRunning = true, simulated = true) }
        }
        composeRule.onNodeWithText("SIMULATED").assertIsDisplayed()
        composeRule.runOnIdle {
            InsRepository.update { it.copy(serviceRunning = false, simulated = false) }
        }
        composeRule.onNodeWithText("STOPPED").assertIsDisplayed()
    }
}
