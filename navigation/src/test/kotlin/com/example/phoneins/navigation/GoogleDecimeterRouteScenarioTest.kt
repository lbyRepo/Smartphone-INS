package com.example.phoneins.navigation

import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

class GoogleDecimeterRouteScenarioTest {
    @Test
    fun fixedMountRouteShowsOutageDriftReacquisitionStableTiltAndZupt() {
        val route = resource("google_decimeter_2021/Pixel4_ground_truth.csv")
            .useLines { DecimeterRouteScenario.parseGroundTruth(it) }
        assertTrue(route.size >= 190)
        val first = route.first()
        val startNanos = 1_000_000_000L
        val engine = NavigationEngine(MotionProfile.VEHICLE)
        engine.initializeManual(
            ManualInitialization(first.latitudeDeg, first.longitudeDeg, first.altitudeM, 0.0, 0.0, first.courseDeg),
        )
        engine.processGnss(DecimeterRouteScenario.gnssFix(first, first, startNanos))

        var minimumRoll = Double.POSITIVE_INFINITY
        var maximumRoll = Double.NEGATIVE_INFINITY
        var minimumPitch = Double.POSITIVE_INFINITY
        var maximumPitch = Double.NEGATIVE_INFINITY
        var maximumStationarySpeed = 0.0
        var maximumAidedPositionError = 0.0
        var maximumAidedPositionErrorIndex = -1
        var maximumAidedPositionFlags = 0
        var maximumAidedVelocityError = 0.0
        var maximumAidedVelocityErrorIndex = -1
        var maximumOutageSeparation = 0.0
        var finalOutageSeparation = 0.0
        var reacquiredSeparation = Double.POSITIVE_INFINITY
        var maximumYawExcursion = 0.0
        var suspendedFixes = 0

        for (index in 1 until route.size) {
            DecimeterRouteScenario.imuBetween(route[index - 1], route[index], first, startNanos).forEach(engine::processImu)
            val fix = DecimeterRouteScenario.gnssFix(route[index], first, startNanos)
            val aidingEnabled = index !in DecimeterRouteScenario.OUTAGE_START_INDEX until DecimeterRouteScenario.OUTAGE_END_INDEX
            if (aidingEnabled) engine.processGnss(fix) else engine.observeGnssWithoutAiding(fix)
            val solution = engine.snapshot(fix.monotonicNanos, fix.unixMillis)
            val positionError = horizontalDistanceM(
                solution.latitudeDeg,
                solution.longitudeDeg,
                fix.latitudeDeg,
                fix.longitudeDeg,
            )
            val velocityError = hypot(
                solution.velocityNedMps.x - fix.velocityNedMps.x,
                solution.velocityNedMps.y - fix.velocityNedMps.y,
            )
            minimumRoll = minOf(minimumRoll, solution.eulerDeg.x)
            maximumRoll = maxOf(maximumRoll, solution.eulerDeg.x)
            minimumPitch = minOf(minimumPitch, solution.eulerDeg.y)
            maximumPitch = maxOf(maximumPitch, solution.eulerDeg.y)
            maximumYawExcursion = maxOf(
                maximumYawExcursion,
                angularDistanceDeg(solution.eulerDeg.z, first.courseDeg),
            )

            if (route[index].speedMps < 0.05 && route[index - 1].speedMps < 0.05 && index > 3) {
                maximumStationarySpeed = maxOf(maximumStationarySpeed, hypot(solution.velocityNedMps.x, solution.velocityNedMps.y))
            }
            val steadilyAided = index < DecimeterRouteScenario.OUTAGE_START_INDEX || index >= DecimeterRouteScenario.OUTAGE_END_INDEX + 2
            if (!aidingEnabled) {
                suspendedFixes++
                maximumOutageSeparation = maxOf(maximumOutageSeparation, positionError)
                finalOutageSeparation = positionError
                assertTrue(solution.latestGnss == fix, "Raw GNSS must remain visible while aiding is off")
                assertTrue(solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0)
            } else if (steadilyAided) {
                if (positionError > maximumAidedPositionError) {
                    maximumAidedPositionError = positionError
                    maximumAidedPositionErrorIndex = index
                    maximumAidedPositionFlags = solution.qualityFlags
                }
                if (velocityError > maximumAidedVelocityError) {
                    maximumAidedVelocityError = velocityError
                    maximumAidedVelocityErrorIndex = index
                }
            }
            if (index == DecimeterRouteScenario.OUTAGE_END_INDEX + 2) reacquiredSeparation = positionError
        }

        val rollRange = maximumRoll - minimumRoll
        val pitchRange = maximumPitch - minimumPitch
        val passed = maximumStationarySpeed < 0.12 && rollRange < 3.0 && pitchRange < 3.0 &&
            maximumYawExcursion > 45.0 &&
            maximumOutageSeparation > 2.0 && reacquiredSeparation < 7.6 &&
            maximumAidedPositionError < 7.6 && maximumAidedVelocityError < 0.65
        val report = buildString {
            appendLine("Google Smartphone Decimeter route · fixed-mounted phone scenario")
            appendLine("Published route epochs: ${route.size}")
            appendLine("Forced aiding-off fixes: $suspendedFixes")
            appendLine("Maximum outage INS–GNSS separation: ${"%.3f".format(maximumOutageSeparation)} m")
            appendLine("Final outage separation: ${"%.3f".format(finalOutageSeparation)} m")
            appendLine("Separation after three reacquisition fixes: ${"%.3f".format(reacquiredSeparation)} m")
            appendLine("Maximum aided position error: ${"%.3f".format(maximumAidedPositionError)} m")
            appendLine("Maximum aided position error index / flags: $maximumAidedPositionErrorIndex / 0x${maximumAidedPositionFlags.toString(16)}")
            appendLine("Maximum aided horizontal velocity error: ${"%.3f".format(maximumAidedVelocityError)} m/s")
            appendLine("Maximum aided velocity error index: $maximumAidedVelocityErrorIndex")
            appendLine("Roll range: ${"%.3f".format(rollRange)} deg")
            appendLine("Pitch range: ${"%.3f".format(pitchRange)} deg")
            appendLine("Maximum heading excursion: ${"%.3f".format(maximumYawExcursion)} deg")
            appendLine("Maximum stationary horizontal speed: ${"%.4f".format(maximumStationarySpeed)} m/s")
            appendLine("Result: ${if (passed) "PASS" else "FAIL"}")
        }
        File("build/reports/google-decimeter-route.txt").apply { parentFile.mkdirs(); writeText(report) }
        println(report)
        assertTrue(passed, report)
    }

    private fun resource(path: String) = checkNotNull(javaClass.classLoader.getResource(path)) { "Missing $path" }.let { File(it.toURI()) }

    private fun horizontalDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val north = (lat2 - lat1) * 111_132.0
        val east = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return hypot(north, east)
    }

    private fun angularDistanceDeg(first: Double, second: Double): Double =
        abs(((first - second + 540.0) % 360.0) - 180.0)
}
