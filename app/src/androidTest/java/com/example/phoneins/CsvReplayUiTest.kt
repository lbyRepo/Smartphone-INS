package com.example.phoneins

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.phoneins.data.CsvReplayPhase
import com.example.phoneins.data.CsvReplayUiState
import com.example.phoneins.data.InsUiState
import com.example.phoneins.data.TrialLogSummary
import com.example.phoneins.service.NavigationService
import com.example.phoneins.ui.PhoneInsTheme
import com.example.phoneins.ui.SessionsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CsvReplayUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun recentLogAndDocumentPickerDispatchExpectedCallbacks() {
        var choseFile = false
        var action: String? = null
        var extras: Map<String, Any> = emptyMap()
        composeRule.setContent {
            PhoneInsTheme {
                SessionsScreen(replayState(), { sentAction, sentExtras ->
                    action = sentAction
                    extras = sentExtras
                }) { choseFile = true }
            }
        }

        composeRule.onNodeWithTag("choose_csv_replay").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertTrue(choseFile) }
        composeRule.onAllNodesWithTag("recent_csv_log")[0].performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(NavigationService.ACTION_CSV_REPLAY_SELECT, action)
            assertEquals("/logs/trial.csv", extras[NavigationService.EXTRA_LOG_ID])
        }
    }

    @Test
    fun readyReplayShowsTimelineSpeedAndPlaybackControls() {
        var action: String? = null
        composeRule.setContent {
            PhoneInsTheme {
                SessionsScreen(replayState(), { sentAction, _ -> action = sentAction })
            }
        }

        composeRule.onNodeWithTag("csv_replay_controls").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("csv_replay_seek").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1.0×").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("csv_replay_play_pause").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertEquals(NavigationService.ACTION_CSV_REPLAY_PLAY, action) }
    }

    @Test
    fun replayControlsRemainReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PhoneInsTheme { SessionsScreen(replayState(), { _, _ -> }) }
            }
        }

        composeRule.onNodeWithTag("csv_replay_play_pause").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("csv_replay_return").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    private fun replayState() = InsUiState(
        serviceRunning = true,
        recentTrialLogs = listOf(
            TrialLogSummary("/logs/trial.csv", "phoneins_trial_20260818_120000.csv", 1_776_000_000_000L, 65_000L, 6_501, 2_000_000L),
        ),
        csvReplay = CsvReplayUiState(
            phase = CsvReplayPhase.READY,
            selectedLogId = "/logs/trial.csv",
            selectedLogName = "phoneins_trial_20260818_120000.csv",
            durationMillis = 65_000L,
            rowCount = 6_501,
            speed = 1.0,
            message = "Ready to reprocess 6501 rows",
        ),
    )
}
