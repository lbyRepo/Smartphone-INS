package com.example.phoneins.navigation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationEngineTest {
    @Test
    fun manualInitializationPreservesWgs84Pose() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 1.0, -2.0, 45.0))
        val solution = engine.snapshot(1_000_000_000, 1_700_000_000_000)
        assertTrue(solution.initialized)
        assertEquals(InitSource.MANUAL, solution.initializationSource)
        assertTrue(abs(solution.latitudeDeg - 1.3521) < 1e-7)
        assertTrue(abs(solution.longitudeDeg - 103.8198) < 1e-7)
        assertTrue(abs(solution.altitudeEllipsoidM - 25.0) < 0.02)
        assertTrue(abs(solution.eulerDeg.z - 45.0) < 1e-6)
    }

    @Test
    fun validGnssPullsSolutionTowardMeasurement() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(37.0, -122.0, 10.0, 0.0, 0.0, 0.0))
        val fix = GnssFix(
            2_000_000_000, 1_700_000_000_000, 37.00001, -121.99999, 11.0,
            Vec3(5.0, 0.0, 0.0), 2.0, 4.0, 0.2, 5.0, 16,
        )
        engine.processGnss(fix)
        val solution = engine.snapshot(2_010_000_000, fix.unixMillis)
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_FRESH != 0)
        assertTrue(solution.latitudeDeg > 37.0)
        assertTrue(solution.longitudeDeg > -122.0)
    }

    @Test
    fun gnssOutageIsFlaggedWithoutStoppingOutput() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.0, 2.0, 3.0, 0.0, 0.0, 0.0))
        val fresh = engine.snapshot(1_000_000_000, 0)
        val stale = engine.snapshot(4_000_000_000, 3_000)
        assertTrue(fresh.initialized)
        assertTrue(stale.degraded)
        assertTrue(stale.qualityFlags and QualityFlag.DEAD_RECKONING != 0)
    }

    @Test
    fun magnetometerInitializesYawButMovingHeadingComesFromGnssCourse() {
        val engine = NavigationEngine(MotionProfile.VEHICLE)
        val initialFix = GnssFix(
            1_000_000_000, 1_700_000_000_000, 1.3521, 103.8198, 25.0,
            Vec3.ZERO, 2.0, 4.0, 0.2, 5.0, 16,
        )
        engine.initializeAutomatic(
            initialFix,
            Vec3(0.0, 0.0, -9.80665),
            Vec3.ZERO,
            Vec3(30.0, 0.0, 35.0),
            0.0,
        )
        val initialized = engine.snapshot(1_010_000_000, initialFix.unixMillis)
        assertTrue(initialized.qualityFlags and QualityFlag.MAGNETOMETER_INITIALIZATION != 0)
        assertTrue(minOf(abs(initialized.eulerDeg.z), abs(initialized.eulerDeg.z - 360.0)) < 1.0)

        repeat(3) { index ->
            engine.processGnss(
                initialFix.copy(
                    monotonicNanos = 2_000_000_000L + index * 1_000_000_000L,
                    unixMillis = initialFix.unixMillis + 1_000L + index * 1_000L,
                    velocityNedMps = Vec3(0.0, 8.0, 0.0),
                    speedAccuracyMps = 0.18,
                    bearingAccuracyDeg = 4.0,
                ),
            )
        }
        val moving = engine.snapshot(4_010_000_000L, initialFix.unixMillis + 3_010)
        assertTrue(moving.qualityFlags and QualityFlag.HEADING_COURSE_AIDED != 0)
        assertTrue(moving.eulerDeg.z > 20.0, "GNSS course did not rotate heading toward East: ${moving.eulerDeg.z}")
        assertTrue(moving.eulerDeg.z < 90.0)
    }

    @Test
    fun implausibleGnssPositionOutlierIsRejected() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0))
        engine.processGnss(
            GnssFix(
                2_000_000_000, 1_700_000_001_000, 2.3521, 104.8198, 25.0,
                Vec3.ZERO, 1.0, 2.0, 0.2, 5.0, 18,
                hasHorizontalVelocity = false,
            ),
        )
        val solution = engine.snapshot(2_010_000_000, 1_700_000_001_010)
        assertTrue(abs(solution.latitudeDeg - 1.3521) < 0.001)
        assertTrue(abs(solution.longitudeDeg - 103.8198) < 0.001)
    }
}
