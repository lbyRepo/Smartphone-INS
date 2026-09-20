package com.example.phoneins.navigation

import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Synthetic-references 45-second outage acceptance, reproducing the interval recorded in
 * `outputs/verification-45s-v0.3.15/selection.md`: the published Google Smartphone Decimeter
 * route replayed through `DecimeterRouteScenario` (synthetic IMU from the reference route),
 * aided through t = 100 s, then aiding withheld for t = 100.000-145.000 s.
 *
 * The fixed limits are asserted: maximum horizontal INS-GNSS separation including the endpoint
 * before reacquisition <=150 m, and <=30 deg INS/reference velocity direction error at every
 * withheld epoch whose reference speed is >=1 m/s. Limit violations fail this test.
 */
class SyntheticOutageAcceptanceTest {
    private val outageStartIndex = 100
    private val outageEndIndex = 145
    private val reacquiredIndex = outageEndIndex + 3

    @Test
    fun syntheticReferenceIntervalPassesFixedOutageLimits() {
        val route = resource("google_decimeter_2021/Pixel4_ground_truth.csv")
            .useLines { DecimeterRouteScenario.parseGroundTruth(it) }
        assertTrue(route.size > reacquiredIndex, "Route is too short for the selected interval")
        val first = route.first()
        val startNanos = 1_000_000_000L
        val engine = NavigationEngine(MotionProfile.VEHICLE)
        engine.initializeManual(
            ManualInitialization(first.latitudeDeg, first.longitudeDeg, first.altitudeM, 0.0, 0.0, first.courseDeg),
        )
        engine.setGnssAidingEnabled(true)

        var aidingEnabled = true
        var maximumSeparation = 0.0
        var endpointSeparation = Double.NaN
        var maximumDirectionError = 0.0
        var directionSamples = 0
        var directionViolations = 0
        var zeroVelocityViolations = 0
        var hiddenCorrectionEpochs = 0
        var reacquiredSeparation = Double.POSITIVE_INFINITY
        var outageEpochs = 0
        val epochs = StringBuilder("t_s,index,withheld,ins_lat,ins_lon,ins_vn,ins_ve,ins_speed,ref_speed,separation_m,direction_err_deg,roll_deg,pitch_deg,yaw_deg,abx,aby,abz,gbx,gby,gbz,flags\n")

        for (index in 0..reacquiredIndex) {
            DecimeterRouteScenario.imuBetween(route[maxOf(index - 1, 0)], route[index], first, startNanos)
                .forEach(engine::processImu)
            val fix = DecimeterRouteScenario.gnssFix(route[index], first, startNanos)

            // Aiding is withheld for the whole interval including the endpoint; the endpoint is
            // evaluated before reacquisition, which begins at the next epoch.
            val withhold = index in outageStartIndex..outageEndIndex
            if (withhold != !aidingEnabled) {
                aidingEnabled = !withhold
                engine.setGnssAidingEnabled(aidingEnabled)
            }
            engine.processGnss(fix)
            val solution = engine.snapshot(fix.monotonicNanos, fix.unixMillis)

            val separation = horizontalDistanceM(
                solution.latitudeDeg, solution.longitudeDeg, fix.latitudeDeg, fix.longitudeDeg,
            )
            val referenceSpeed = hypot(fix.velocityNedMps.x, fix.velocityNedMps.y)
            if (withhold) {
                outageEpochs++
                maximumSeparation = maxOf(maximumSeparation, separation)
                if (index == outageEndIndex) endpointSeparation = separation
                // The aided-valid flags persist for GNSS_STALE_NANOS (2 s) after the last
                // accepted update, so allow that staleness tail and require the flags to be
                // clear for the remainder of the withheld interval.
                if (index >= outageStartIndex + 3 &&
                    (solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0 ||
                        solution.qualityFlags and QualityFlag.GNSS_HORIZONTAL_VELOCITY_VALID != 0)
                ) {
                    hiddenCorrectionEpochs++
                }
                if (referenceSpeed >= 1.0) {
                    directionSamples++
                    val insSpeed = hypot(solution.velocityNedMps.x, solution.velocityNedMps.y)
                    if (insSpeed < 1e-6) {
                        zeroVelocityViolations++
                    } else {
                        val insAngle = Math.toDegrees(Math.atan2(solution.velocityNedMps.y, solution.velocityNedMps.x))
                        val referenceAngle = Math.toDegrees(Math.atan2(fix.velocityNedMps.y, fix.velocityNedMps.x))
                        val error = angularDistanceDeg(insAngle, referenceAngle)
                        maximumDirectionError = maxOf(maximumDirectionError, error)
                        if (error > 30.0) directionViolations++
                    }
                }
            } else if (index == reacquiredIndex) {
                reacquiredSeparation = separation
            }
            val insSpeedForLog = hypot(solution.velocityNedMps.x, solution.velocityNedMps.y)
            epochs.append(
                "%.3f,%d,%d,%.7f,%.7f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%.2f,%.2f,%.2f,%.5f,%.5f,%.5f,%.6f,%.6f,%.6f,%d%n".format(
                    index.toDouble(), index, if (withhold) 1 else 0,
                    solution.latitudeDeg, solution.longitudeDeg,
                    solution.velocityNedMps.x, solution.velocityNedMps.y, insSpeedForLog, referenceSpeed,
                    separation,
                    if (referenceSpeed >= 1.0 && insSpeedForLog >= 1e-6) {
                        angularDistanceDeg(
                            Math.toDegrees(Math.atan2(solution.velocityNedMps.y, solution.velocityNedMps.x)),
                            Math.toDegrees(Math.atan2(fix.velocityNedMps.y, fix.velocityNedMps.x)),
                        )
                    } else Double.NaN,
                    solution.eulerDeg.x, solution.eulerDeg.y, solution.eulerDeg.z,
                    solution.accelerometerBiasMps2.x, solution.accelerometerBiasMps2.y, solution.accelerometerBiasMps2.z,
                    solution.gyroBiasRadps.x, solution.gyroBiasRadps.y, solution.gyroBiasRadps.z,
                    solution.qualityFlags,
                ),
            )
        }

        val report = buildString {
            appendLine("Synthetic reference 45 s outage acceptance · DecimeterRouteScenario")
            appendLine("Interval: ${outageStartIndex}.000-${outageEndIndex}.000 s (aided through ${outageStartIndex}.000 s)")
            appendLine("Withheld epochs evaluated: $outageEpochs")
            appendLine("Maximum separation: ${"%.3f".format(maximumSeparation)} m (limit 150 m)")
            appendLine("Endpoint separation before reacquisition: ${"%.3f".format(endpointSeparation)} m")
            appendLine("Maximum direction error: ${"%.3f".format(maximumDirectionError)} deg (limit 30 deg)")
            appendLine("Direction samples / violations: $directionSamples / $directionViolations")
            appendLine("Zero-velocity violations: $zeroVelocityViolations")
            appendLine("Epochs with GNSS correction flags while withheld: $hiddenCorrectionEpochs")
            appendLine("Separation three fixes after reacquisition: ${"%.3f".format(reacquiredSeparation)} m")
            appendLine(
                "Verdict: " + if (outageEpochs > 0 && directionSamples > 0 &&
                    maximumSeparation <= 150.0 && directionViolations == 0 && zeroVelocityViolations == 0 &&
                    hiddenCorrectionEpochs == 0 && reacquiredSeparation < 7.6
                ) "PASS" else "FAIL",
            )
        }
        File("build/reports/synthetic-outage-acceptance.txt").apply { parentFile.mkdirs(); writeText(report) }
        File("build/reports/synthetic-outage-epochs.csv").apply { parentFile.mkdirs(); writeText(epochs.toString()) }
        println(report)

        assertTrue(outageEpochs > 0 && endpointSeparation.isFinite(), "Incomplete outage coverage:\n$report")
        assertTrue(directionSamples > 0, "No moving reference epochs inside the outage:\n$report")
        assertTrue(maximumSeparation <= 150.0, "Maximum separation ${maximumSeparation} m exceeds 150 m:\n$report")
        assertTrue(directionViolations == 0 && zeroVelocityViolations == 0,
            "Direction violations=$directionViolations, zero-velocity=$zeroVelocityViolations:\n$report")
        assertTrue(hiddenCorrectionEpochs == 0, "GNSS corrections applied while aiding was withheld:\n$report")
        assertTrue(reacquiredSeparation < 7.6, "Aiding did not recover after the outage:\n$report")
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
