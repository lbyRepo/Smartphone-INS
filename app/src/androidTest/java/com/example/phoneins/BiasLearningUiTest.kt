package com.example.phoneins

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.example.phoneins.navigation.QualityFlag
import com.example.phoneins.ui.PhoneInsTheme
import com.example.phoneins.ui.RawDataScreen
import com.example.phoneins.ui.previewState
import org.junit.Rule
import org.junit.Test

class BiasLearningUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun qualityStatusIsExplicitAndReachableAtLargeFont() {
        val base = previewState()
        val state = mutableStateOf(base.copy(solution = base.solution.copy(qualityFlags = QualityFlag.INITIALIZED or QualityFlag.BIAS_LEARNING_HELD)))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PhoneInsTheme { RawDataScreen(state.value) }
            }
        }
        composeRule.onNodeWithTag("raw_data").performScrollToNode(hasTestTag("bias_learning_status"))
        composeRule.onNodeWithText("Bias learning paused · motion, IMU quality or recovery").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { state.value = base.copy(solution = base.solution.copy(qualityFlags = QualityFlag.INITIALIZED or QualityFlag.IMU_CLIPPED)) }
        composeRule.onNodeWithText("Bias learning paused · possible IMU clipping").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { state.value = base.copy(solution = base.solution.copy(qualityFlags = QualityFlag.INITIALIZED)) }
        composeRule.onNodeWithText("Clean IMU · gyro bias learning requires stillness").performScrollTo().assertIsDisplayed()
    }
}
