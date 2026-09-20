package com.example.phoneins

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.phoneins.data.InsRepository
import com.example.phoneins.navigation.FixStatus
import com.example.phoneins.navigation.DecimeterRouteScenario
import com.example.phoneins.navigation.GnssFix
import com.example.phoneins.navigation.InitSource
import com.example.phoneins.navigation.ManualInitialization
import com.example.phoneins.navigation.MotionProfile
import com.example.phoneins.navigation.NavigationEngine
import com.example.phoneins.navigation.QualityFlag
import com.example.phoneins.navigation.Vec3
import com.example.phoneins.ui.PositionMapState
import com.example.phoneins.ui.PositionPlotData
import com.example.phoneins.ui.PositionPlotPalette
import com.example.phoneins.ui.PositionPlotView
import com.example.phoneins.ui.clearStoredMapBasemap
import com.example.phoneins.ui.toLocalMetresForTest
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
    fun storageFailuresAreVisibleInMorePage() {
        composeRule.runOnIdle {
            InsRepository.update { it.copy(loggingError = "Logging failed: Disk full", droppedLogRecords = 12) }
        }
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.onNodeWithText("Logging failed: Disk full").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Dropped log records").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun serviceBadgeReflectsRepositoryState() {
        composeRule.runOnIdle {
            InsRepository.update { it.copy(serviceRunning = true, simulated = true) }
        }
        composeRule.onNodeWithContentDescription("Navigation service: simulated").assertIsDisplayed()
        composeRule.runOnIdle {
            InsRepository.update { it.copy(serviceRunning = false, simulated = false) }
        }
        composeRule.onNodeWithContentDescription("Navigation service: stopped").assertIsDisplayed()
    }

    @Test
    fun dashboardContainsInsAndRawGnssPositionMap() {
        composeRule.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("position_map"))
        composeRule.onNodeWithTag("position_map").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Position map with 0 INS history points and 0 raw GNSS history points · street map").fetchSemanticsNode()
        composeRule.onNodeWithText("INS position").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Raw GNSS").assertIsDisplayed()
    }

    @Test
    fun positionMapBasemapToggleSwitchesStreetMapAndGridModes() {
        composeRule.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("map_mode_tiles"))
        composeRule.onNodeWithContentDescription("Position map with 0 INS history points and 0 raw GNSS history points · street map").assertExists()
        composeRule.onNodeWithTag("map_mode_grid").performClick()
        composeRule.onNodeWithContentDescription("Position map with 0 INS history points and 0 raw GNSS history points · grid").assertExists()
        composeRule.onNodeWithTag("map_mode_tiles").performClick()
        composeRule.onNodeWithContentDescription("Position map with 0 INS history points and 0 raw GNSS history points · street map").assertExists()
        // Restore the default choice so order-independent tests keep seeing the street map.
        clearStoredMapBasemap(composeRule.activity)
    }

    @Test
    fun dashboardShowsInitializationCompletionState() {
        composeRule.onNodeWithTag("initialization_status").assertIsDisplayed()
        composeRule.onNodeWithTag("initialization_status")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not initialized"))
        composeRule.onNodeWithText("Waiting for initialization").assertDoesNotExist()

        composeRule.runOnIdle {
            InsRepository.update {
                it.copy(
                    serviceRunning = true,
                    initializationInProgress = true,
                    initializationProgress = 0.5f,
                    initializationElapsedSeconds = 7.5,
                    coarseRollDeg = 1.2,
                    coarsePitchDeg = -2.3,
                    statusMessage = "Initializing · 7.5/15.0 s · keep still",
                )
            }
        }
        composeRule.onNodeWithText("Initializing").assertIsDisplayed()
        composeRule.onNodeWithTag("initialization_progress_text").assertDoesNotExist()
        composeRule.onNodeWithTag("coarse_alignment").assertDoesNotExist()
        composeRule.onNodeWithTag("nav_initialize").performClick()
        composeRule.onNodeWithTag("init_screen_progress_text").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("coarse_alignment").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("nav_dashboard").performClick()

        composeRule.runOnIdle {
            InsRepository.update { state ->
                state.copy(
                    initializationInProgress = false,
                    solution = state.solution.copy(
                        initializationSource = InitSource.MANUAL,
                        qualityFlags = QualityFlag.INITIALIZED,
                    ),
                    statusMessage = "Manual pose initialized · zero velocity",
                )
            }
        }
        composeRule.onNodeWithText("Initialized").assertIsDisplayed()
        composeRule.onNodeWithTag("initialization_progress_text").assertDoesNotExist()
    }

    @Test
    fun initializationFailureDetailsStayOnInitAndRetryClearsFailure() {
        composeRule.runOnIdle {
            InsRepository.update { it.copy(initializationError = "Invalid alignment pose") }
        }
        composeRule.onNodeWithText("Initialization failed").assertIsDisplayed()
        composeRule.onNodeWithText("Invalid alignment pose").assertDoesNotExist()
        composeRule.onNodeWithText("Waiting for initialization").assertDoesNotExist()
        composeRule.onNodeWithTag("nav_initialize").performClick()
        composeRule.onNodeWithTag("initialization_error").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            InsRepository.update { it.copy(initializationError = null, initializationInProgress = true) }
        }
        composeRule.onNodeWithTag("nav_dashboard").performClick()
        composeRule.onNodeWithText("Initializing").assertIsDisplayed()
        composeRule.onNodeWithText("Initialization failed").assertDoesNotExist()
    }

    @Test
    fun aidedDashboardQuantifiesInsGnssSeparationAndBound() {
        val now = 10_000_000_000L
        val gnss = GnssFix(
            now - 100_000_000L,
            1_750_000_000_000L,
            1.3521,
            103.8198,
            25.0,
            Vec3.ZERO,
            2.5,
            4.0,
            0.2,
            180.0,
            12,
        )
        composeRule.runOnIdle {
            InsRepository.update { state ->
                state.copy(
                    solution = state.solution.copy(
                        monotonicNanos = now,
                        latitudeDeg = 1.35211,
                        longitudeDeg = 103.81981,
                        altitudeEllipsoidM = 25.2,
                        latestGnss = gnss,
                        fixStatus = FixStatus.FIX_3D,
                        qualityFlags = QualityFlag.INITIALIZED or QualityFlag.GNSS_FRESH or QualityFlag.GNSS_POSITION_VALID,
                    ),
                )
            }
        }
        composeRule.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("position_separation"))
        composeRule.onNodeWithTag("position_separation").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("GNSS bound active · within 7.5 m").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun positionMapOriginStaysAtInitializationLocationWhileInsMoves() {
        val now = 20_000_000_000L
        composeRule.runOnIdle {
            InsRepository.update { state ->
                state.copy(
                    solution = state.solution.copy(
                        monotonicNanos = now,
                        sequence = 10,
                        latitudeDeg = 1.3521,
                        longitudeDeg = 103.8198,
                        altitudeEllipsoidM = 25.0,
                        fixStatus = FixStatus.FIX_3D,
                        qualityFlags = QualityFlag.INITIALIZED or QualityFlag.GNSS_POSITION_VALID,
                    ),
                )
            }
        }
        composeRule.onNodeWithTag("dashboard").performScrollToNode(hasText("Origin 1.3521000, 103.8198000"))
        composeRule.onNodeWithText("Origin 1.3521000, 103.8198000").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            InsRepository.update { state ->
                state.copy(
                    solution = state.solution.copy(
                        monotonicNanos = now + 1_000_000_000L,
                        sequence = 110,
                        latitudeDeg = 1.3522,
                        longitudeDeg = 103.8199,
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Origin 1.3521000, 103.8198000").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("History · INS 2 points · GNSS 0 points").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("INS displacement 15.7 m from initialization").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Position map with 2 INS history points and 0 raw GNSS history points · street map").fetchSemanticsNode()
    }

    @Test
    fun nativePositionPlotDrawsHistoricalInsPolylinePixels() {
        var insPixels = 0
        composeRule.runOnIdle {
            val width = 800
            val height = 600
            val insColor = Color.rgb(0, 106, 101)
            val view = PositionPlotView(composeRule.activity).apply {
                render(
                    PositionPlotData(
                        insLocalMetres = (0..60).map { index ->
                            Vec3(index * 2.0, kotlin.math.sin(index * 0.22) * 45.0, 0.0)
                        },
                        gnssLocalMetres = emptyList(),
                        centreNorthMetres = 60.0,
                        centreEastMetres = 0.0,
                        halfSpanMetres = 75.0,
                        hasOrigin = true,
                    ),
                    PositionPlotPalette(
                        background = Color.rgb(235, 232, 238),
                        grid = Color.rgb(190, 187, 192),
                        foreground = Color.BLACK,
                        ins = insColor,
                        gnss = Color.rgb(69, 97, 121),
                    ),
                )
                measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, width, height)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            insPixels = pixels.count { it == insColor }
        }

        org.junit.Assert.assertTrue(
            "Expected a visible multi-segment INS trail, found only $insPixels solid trail pixels",
            insPixels > 2_000,
        )
    }

    @Test
    fun decimeterRouteMapRetainsWholeRunAcrossOutageDriftAndReacquisition() {
        val testOutageRange = 105 until 125
        val reacquiredIndex = testOutageRange.last + 3
        val route = composeRule.activity.assets.open("google_decimeter_2021/Pixel4_ground_truth.csv")
            .bufferedReader()
            .useLines { DecimeterRouteScenario.parseGroundTruth(it) }
        val map = PositionMapState()
        val engine = NavigationEngine(MotionProfile.VEHICLE)
        val first = route.first()
        val startNanos = 30_000_000_000L
        engine.initializeManual(
            ManualInitialization(first.latitudeDeg, first.longitudeDeg, first.altitudeM, 0.0, 0.0, first.courseDeg),
        )
        val firstFix = DecimeterRouteScenario.gnssFix(first, first, startNanos)
        engine.processGnss(firstFix)
        map.update(engine.snapshot(firstFix.monotonicNanos, firstFix.unixMillis))
        var maximumOutageSeparation = 0.0
        var reacquiredSeparation = Double.POSITIVE_INFINITY

        for (index in 1 until route.size) {
            DecimeterRouteScenario.imuBetween(route[index - 1], route[index], first, startNanos).forEach(engine::processImu)
            val fix = DecimeterRouteScenario.gnssFix(route[index], first, startNanos)
            val aidingEnabled = index !in testOutageRange
            if (aidingEnabled) engine.processGnss(fix) else engine.observeGnssWithoutAiding(fix)
            val solution = engine.snapshot(fix.monotonicNanos, fix.unixMillis)
            map.update(solution)
            val separation = map.insTrail.last().toLocalMetresForTest(map.gnssTrail.last()).norm()
            if (!aidingEnabled) maximumOutageSeparation = maxOf(maximumOutageSeparation, separation)
            if (index == reacquiredIndex) reacquiredSeparation = separation
        }

        org.junit.Assert.assertEquals(route.size, map.insTrail.size)
        org.junit.Assert.assertEquals(route.size, map.gnssTrail.size)
        org.junit.Assert.assertEquals(first.latitudeDeg, map.origin?.latitudeDeg ?: Double.NaN, 1e-10)
        org.junit.Assert.assertTrue(map.insTrail.last().toLocalMetresForTest(map.origin!!).norm() > 100.0)
        org.junit.Assert.assertTrue(maximumOutageSeparation > 2.0)
        org.junit.Assert.assertTrue(reacquiredSeparation < 7.6)
    }

    @Test
    fun rejectedGnssUpdateIsNotDescribedAsAided() {
        composeRule.runOnIdle {
            InsRepository.update { state ->
                state.copy(
                    solution = state.solution.copy(
                        fixStatus = FixStatus.FIX_3D,
                        qualityFlags = QualityFlag.INITIALIZED or QualityFlag.GNSS_FRESH or QualityFlag.GNSS_UPDATE_REJECTED,
                    ),
                )
            }
        }
        composeRule.onNodeWithText("GNSS fix present · aiding rejected").assertIsDisplayed()
    }

    @Test
    fun intentionallySuspendedAidingIsClearlyDistinguishedFromMissingGnss() {
        composeRule.runOnIdle {
            InsRepository.update { state ->
                state.copy(
                    solution = state.solution.copy(
                        fixStatus = FixStatus.FIX_3D,
                        qualityFlags = QualityFlag.INITIALIZED or QualityFlag.GNSS_FRESH or
                            QualityFlag.GNSS_AIDING_SUSPENDED or QualityFlag.DEGRADED or QualityFlag.DEAD_RECKONING,
                    ),
                )
            }
        }
        composeRule.onNodeWithText("GNSS visible · aiding paused").assertDoesNotExist()
        composeRule.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("gnss_outage_toggle"))
        composeRule.onNodeWithTag("gnss_outage_toggle").assertIsDisplayed()
    }
}
