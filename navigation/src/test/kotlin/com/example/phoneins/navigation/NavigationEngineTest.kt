package com.example.phoneins.navigation

import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_UPDATE_REJECTED != 0)
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID == 0)
    }

    @Test
    fun stationaryPhoneRemainsBoundedWithBiasedImuAndNoisyThreeDimensionalGnss() {
        val latitude = 1.3521
        val longitude = 103.8198
        val altitude = 25.0
        val engine = NavigationEngine(MotionProfile.PEDESTRIAN)
        engine.initializeManual(ManualInitialization(latitude, longitude, altitude, 0.0, 0.0, 0.0))

        val start = 1_000_000_000L
        var settledEuler = Vec3.ZERO
        var fullyAidedGnssFixes = 0
        repeat(12_001) { index ->
            val now = start + index * 10_000_000L
            val seconds = index / 100.0
            engine.processImu(
                TimedImuSample(
                    now,
                    Vec3(
                        0.10 + 0.008 * sin(seconds * 0.7),
                        -0.07 + 0.006 * cos(seconds * 0.5),
                        -9.70 + 0.006 * sin(seconds * 0.3),
                    ),
                    Vec3(
                        0.0025 + 0.0004 * sin(seconds * 0.9),
                        -0.0018 + 0.0003 * cos(seconds * 0.8),
                        0.0032 + 0.0005 * sin(seconds * 1.1),
                    ),
                ),
            )
            if (index % 100 == 0) {
                val northNoise = 1.8 * sin(seconds * 0.37) + 0.5 * sin(seconds * 1.7)
                val eastNoise = 1.6 * cos(seconds * 0.31) + 0.4 * cos(seconds * 1.3)
                engine.processGnss(
                    stationaryFix(
                        now,
                        latitude,
                        longitude,
                        altitude,
                        northNoise,
                        eastNoise,
                        0.8 * sin(seconds * 0.2),
                        hasHorizontalVelocity = true,
                    ),
                )
                val aided = engine.snapshot(now, 1_700_000_000_000L + now / 1_000_000L)
                if (aided.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0 &&
                    aided.qualityFlags and QualityFlag.GNSS_HORIZONTAL_VELOCITY_VALID != 0
                ) {
                    fullyAidedGnssFixes++
                }
            }
            if (index == 3_000) {
                settledEuler = engine.snapshot(now, 1_700_000_030_000L).eulerDeg
            }
        }

        val solution = engine.snapshot(start + 120_010_000_000L, 1_700_000_120_010L)
        val horizontalError = horizontalDistanceM(latitude, longitude, solution.latitudeDeg, solution.longitudeDeg)
        val horizontalSpeed = hypot(solution.velocityNedMps.x, solution.velocityNedMps.y)
        val rollDrift = angularDifferenceDeg(solution.eulerDeg.x, settledEuler.x)
        val pitchDrift = angularDifferenceDeg(solution.eulerDeg.y, settledEuler.y)
        val yawDrift = angularDifferenceDeg(solution.eulerDeg.z, settledEuler.z)
        val maximumEulerDrift = maxOf(rollDrift, pitchDrift, yawDrift)
        val report = buildString {
            appendLine("Stationary INS regression · biased IMU + noisy valid 3D GNSS")
            appendLine("Duration: 120.0 s")
            appendLine("IMU samples: 12,001 at 100 Hz")
            appendLine("GNSS fixes: 121 at 1 Hz")
            appendLine("Position + velocity aided fixes: $fullyAidedGnssFixes / 121")
            appendLine("Final horizontal position error: ${"%.3f".format(horizontalError)} m")
            appendLine("Final horizontal speed: ${"%.4f".format(horizontalSpeed)} m/s")
            appendLine("Final down speed: ${"%.4f".format(solution.velocityNedMps.z)} m/s")
            appendLine("Euler drift after 30 s settling (roll/pitch/yaw): ${"%.4f".format(rollDrift)} / ${"%.4f".format(pitchDrift)} / ${"%.4f".format(yawDrift)} deg")
            appendLine("GNSS position aiding: ${solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0}")
            appendLine("Stationary constraint: ${solution.qualityFlags and QualityFlag.STATIONARY_CONSTRAINT != 0}")
            appendLine("Result: ${if (horizontalError < 3.0 && horizontalSpeed < 0.08 && maximumEulerDrift < 0.2) "PASS" else "FAIL"}")
        }
        File("build/reports/stationary-regression.txt").apply { parentFile.mkdirs(); writeText(report) }
        println(report)
        assertTrue(horizontalError < 3.0, "Stationary position drifted $horizontalError m")
        assertTrue(abs(solution.altitudeEllipsoidM - altitude) < 2.0, "Stationary altitude drifted to ${solution.altitudeEllipsoidM} m")
        assertTrue(horizontalSpeed < 0.08, "Stationary velocity drifted to $horizontalSpeed m/s")
        assertTrue(abs(solution.velocityNedMps.z) < 0.1)
        assertEquals(121, fullyAidedGnssFixes, "Every valid stationary 3D fix should apply position and velocity aiding")
        assertTrue(rollDrift < 0.2, "Stationary roll kept rotating: $rollDrift deg after settling")
        assertTrue(pitchDrift < 0.2, "Stationary pitch kept rotating: $pitchDrift deg after settling")
        assertTrue(yawDrift < 0.2, "Stationary yaw kept rotating: $yawDrift deg after settling")
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0)
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_HORIZONTAL_VELOCITY_VALID != 0)
        assertTrue(solution.qualityFlags and QualityFlag.STATIONARY_CONSTRAINT != 0)
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_UPDATE_REJECTED == 0)
    }

    @Test
    fun stationaryPhoneWithoutGnssBearingStillGetsZeroVelocityConstraint() {
        val latitude = 37.42358
        val longitude = -122.09412
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(latitude, longitude, 30.0, 0.0, 0.0, 20.0))
        val start = 2_000_000_000L
        repeat(501) { index ->
            val now = start + index * 10_000_000L
            engine.processImu(TimedImuSample(now, Vec3(0.06, -0.04, -9.75), Vec3(0.002, -0.001, 0.0025)))
            if (index % 100 == 0) {
                engine.processGnss(
                    stationaryFix(now, latitude, longitude, 30.0, 0.3 * sin(index.toDouble()), 0.3 * cos(index.toDouble()), 0.0, hasHorizontalVelocity = false),
                )
            }
        }
        val solution = engine.snapshot(start + 5_010_000_000L, 1_700_000_005_010L)
        assertTrue(hypot(solution.velocityNedMps.x, solution.velocityNedMps.y) < 0.08)
        assertTrue(solution.qualityFlags and QualityFlag.STATIONARY_CONSTRAINT != 0)
    }

    @Test
    fun stationaryPhoneEulerTracksPhysicalRollAndPitchTilt() {
        val latitude = 37.42358
        val longitude = -122.09412
        val start = 6_000_000_000L

        val rollEngine = NavigationEngine()
        rollEngine.initializeManual(ManualInitialization(latitude, longitude, 30.0, 0.0, 0.0, 0.0))
        rollEngine.processImu(TimedImuSample(start, restingSpecificForceBody(0.0, 0.0), Vec3.ZERO))
        val rollRate = Math.toRadians(30.0)
        repeat(100) { index ->
            val roll = rollRate * (index + 1) / 100.0
            rollEngine.processImu(
                TimedImuSample(
                    start + (index + 1) * 10_000_000L,
                    restingSpecificForceBody(roll, 0.0),
                    Vec3(rollRate, 0.0, 0.0),
                ),
            )
        }
        val rolled = rollEngine.snapshot(start + 1_010_000_000L, 1_700_000_001_010L)
        assertTrue(abs(rolled.eulerDeg.x) > 20.0, "Roll Euler angle stayed latched while the phone rolled: ${rolled.eulerDeg}")

        val pitchEngine = NavigationEngine()
        pitchEngine.initializeManual(ManualInitialization(latitude, longitude, 30.0, 0.0, 0.0, 0.0))
        pitchEngine.processImu(TimedImuSample(start, restingSpecificForceBody(0.0, 0.0), Vec3.ZERO))
        val pitchRate = Math.toRadians(25.0)
        repeat(100) { index ->
            val pitch = pitchRate * (index + 1) / 100.0
            pitchEngine.processImu(
                TimedImuSample(
                    start + (index + 1) * 10_000_000L,
                    restingSpecificForceBody(0.0, pitch),
                    Vec3(0.0, pitchRate, 0.0),
                ),
            )
        }
        val pitched = pitchEngine.snapshot(start + 1_010_000_000L, 1_700_000_001_010L)
        assertTrue(abs(pitched.eulerDeg.y) > 15.0, "Pitch Euler angle stayed latched while the phone tilted: ${pitched.eulerDeg}")
    }

    @Test
    fun stationaryConstraintReleasesWhenMovementAndGnssCourseBegin() {
        val latitude = 37.42358
        val longitude = -122.09412
        val engine = NavigationEngine(MotionProfile.VEHICLE)
        engine.initializeManual(ManualInitialization(latitude, longitude, 30.0, 0.0, 0.0, 0.0))
        val start = 7_000_000_000L
        repeat(301) { index ->
            val now = start + index * 10_000_000L
            engine.processImu(TimedImuSample(now, Vec3(0.0, 0.0, -9.80665), Vec3(0.0, 0.0, 0.004)))
            if (index % 100 == 0) engine.processGnss(
                stationaryFix(now, latitude, longitude, 30.0, 0.0, 0.0, 0.0, hasHorizontalVelocity = true),
            )
        }
        val held = engine.snapshot(start + 3_010_000_000L, 1_700_000_003_010L)
        assertTrue(held.qualityFlags and QualityFlag.STATIONARY_CONSTRAINT != 0)

        repeat(3) { fixIndex ->
            val fixTime = start + 4_000_000_000L + fixIndex * 1_000_000_000L
            engine.processGnss(
                stationaryFix(fixTime, latitude, longitude, 30.0, fixIndex * 8.0, 0.0, 0.0, hasHorizontalVelocity = true)
                    .copy(velocityNedMps = Vec3(0.0, 8.0, 0.0), speedAccuracyMps = 0.2, bearingAccuracyDeg = 4.0),
            )
            repeat(100) { imuIndex ->
                engine.processImu(
                    TimedImuSample(
                        fixTime + (imuIndex + 1) * 10_000_000L,
                        Vec3(0.0, 0.0, -9.80665),
                        Vec3(0.0, 0.0, 0.3),
                    ),
                )
            }
        }
        val moving = engine.snapshot(start + 7_010_000_000L, 1_700_000_007_010L)
        assertTrue(moving.qualityFlags and QualityFlag.STATIONARY_CONSTRAINT == 0)
        assertTrue(moving.qualityFlags and QualityFlag.HEADING_COURSE_AIDED != 0)
        assertTrue(angularDifferenceDeg(moving.eulerDeg.z, held.eulerDeg.z) > 10.0, "Stationary attitude latch did not release")
    }

    @Test
    fun threeConsistentThreeDimensionalFixesReacquireAfterLargeInertialDrift() {
        val latitude = 51.0
        val longitude = -0.1
        val altitude = 40.0
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(latitude, longitude, altitude, 0.0, 0.0, 0.0))
        val start = 3_000_000_000L
        repeat(3_001) { index ->
            val now = start + index * 10_000_000L
            engine.processImu(TimedImuSample(now, Vec3(0.22, 0.0, -9.80665), Vec3(0.0, 0.0, 0.001)))
        }
        repeat(3) { index ->
            val now = start + 31_000_000_000L + index * 1_000_000_000L
            engine.processGnss(stationaryFix(now, latitude, longitude, altitude, index * 0.2, -index * 0.15, 0.1, hasHorizontalVelocity = true))
            repeat(100) { imuIndex ->
                engine.processImu(TimedImuSample(now + (imuIndex + 1) * 10_000_000L, Vec3(0.01, -0.01, -9.80), Vec3.ZERO))
            }
        }
        val now = start + 34_010_000_000L
        val solution = engine.snapshot(now, 1_700_000_034_010L)
        val horizontalError = horizontalDistanceM(latitude, longitude, solution.latitudeDeg, solution.longitudeDeg)
        assertTrue(horizontalError < 5.0, "GNSS reacquisition left $horizontalError m error")
        assertTrue(hypot(solution.velocityNedMps.x, solution.velocityNedMps.y) < 0.15)
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0)
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_UPDATE_REJECTED == 0)
    }

    @Test
    fun invalidThreeDimensionalFixDoesNotClaimGnssAiding() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.0, 2.0, 3.0, 0.0, 0.0, 0.0))
        engine.processGnss(
            stationaryFix(1_000_000_000L, 1.0, 2.0, Double.NaN, 0.0, 0.0, 0.0, hasHorizontalVelocity = true)
                .copy(horizontalAccuracyM = 500.0),
        )
        val solution = engine.snapshot(1_010_000_000L, 1_700_000_000_010L)
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID == 0)
        assertEquals(FixStatus.NO_FIX, solution.fixStatus)
    }

    @Test
    fun consistentMovingGnssVelocityReacquiresAfterVelocityInnovationIsInitiallyRejected() {
        val latitude = 48.0
        val longitude = 11.0
        val engine = NavigationEngine(MotionProfile.VEHICLE)
        engine.initializeManual(ManualInitialization(latitude, longitude, 500.0, 0.0, 0.0, 0.0))
        val start = 4_000_000_000L
        repeat(2_001) { index ->
            engine.processImu(
                TimedImuSample(
                    start + index * 10_000_000L,
                    Vec3(2.0, 0.0, -9.80665),
                    Vec3.ZERO,
                ),
            )
        }
        repeat(3) { index ->
            val now = start + 21_000_000_000L + index * 1_000_000_000L
            val north = 100.0 + index * 5.0
            val fix = stationaryFix(now, latitude, longitude, 500.0, north, 0.0, 0.0, hasHorizontalVelocity = true)
                .copy(velocityNedMps = Vec3(5.0, 0.0, 0.0), bearingAccuracyDeg = 4.0)
            engine.processGnss(fix)
            if (index < 2) repeat(100) { imuIndex ->
                engine.processImu(TimedImuSample(now + (imuIndex + 1) * 10_000_000L, Vec3(0.0, 0.0, -9.80665), Vec3.ZERO))
            }
        }
        val solution = engine.snapshot(start + 23_010_000_000L, 1_700_000_023_010L)
        val expectedLatitude = latitude + 110.0 / 111_132.0
        val positionError = horizontalDistanceM(expectedLatitude, longitude, solution.latitudeDeg, solution.longitudeDeg)
        val velocityError = hypot(solution.velocityNedMps.x - 5.0, solution.velocityNedMps.y)
        assertTrue(positionError <= 7.55, "Moving GNSS position exceeded its 3σ horizontal bound: $positionError m")
        assertTrue(velocityError <= 0.61, "Moving INS velocity exceeded its 3σ GNSS bound: $velocityError m/s")
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_HORIZONTAL_VELOCITY_VALID != 0)
        assertTrue(solution.qualityFlags and QualityFlag.GNSS_UPDATE_REJECTED == 0)
    }

    private fun stationaryFix(
        monotonicNanos: Long,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        northM: Double,
        eastM: Double,
        downM: Double,
        hasHorizontalVelocity: Boolean,
    ): GnssFix {
        val shiftedLatitude = latitude + northM / 111_132.0
        val shiftedLongitude = longitude + eastM / (111_320.0 * cos(Math.toRadians(latitude)))
        return GnssFix(
            monotonicNanos,
            1_700_000_000_000L + monotonicNanos / 1_000_000L,
            shiftedLatitude,
            shiftedLongitude,
            altitude - downM,
            Vec3.ZERO,
            2.5,
            4.0,
            0.2,
            180.0,
            14,
            hasAltitude = true,
            hasHorizontalVelocity = hasHorizontalVelocity,
        )
    }

    private fun horizontalDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val north = (lat2 - lat1) * 111_132.0
        val east = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return hypot(north, east)
    }

    private fun angularDifferenceDeg(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)

    private fun restingSpecificForceBody(rollRad: Double, pitchRad: Double): Vec3 =
        multiply3(
            transpose3(bodyToNedMatrix(rollRad, pitchRad, 0.0)),
            Vec3(0.0, 0.0, -9.80665),
        )
}
