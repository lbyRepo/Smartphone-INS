package com.example.phoneins.service

import com.example.phoneins.navigation.GnssFix
import com.example.phoneins.navigation.NavigationEngine
import com.example.phoneins.navigation.QualityFlag
import com.example.phoneins.navigation.ReplayInitialization
import com.example.phoneins.navigation.TimedImuSample
import com.example.phoneins.navigation.Vec3
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mandatory 45-second GNSS outage verification (AGENTS.md), driven by a logged trial CSV.
 *
 * Reprocesses the logged raw IMU and GNSS through the current [NavigationEngine] exactly as the
 * app's CSV replay does (warm-start from the logged initialization), withholds GNSS aiding for
 * exactly 45 seconds of recorded sensor time, and measures horizontal separation plus
 * INS/GNSS velocity-direction agreement against the contemporaneous GNSS reference.
 *
 * Opt-in: runs only when the TRIAL_CSV environment variable points at a trial CSV.
 * Epoch-level output is written next to the source file for plotting and the report.
 */
class TrialOutageVerificationTest {

    @Test
    fun fortyFiveSecondOutageVerification() {
        val csvPath = System.getenv("TRIAL_CSV")
        assumeTrue("TRIAL_CSV not set; verification harness is opt-in", !csvPath.isNullOrBlank())
        val sourceFile = File(csvPath)
        assumeTrue("TRIAL_CSV does not exist: $csvPath", sourceFile.isFile)

        val index = CsvReplayIndex.open(sourceFile)
        val rows = ArrayList<CsvReplayRow>(index.rowCount)
        index.cursor().use { cursor ->
            while (true) rows += cursor.next() ?: break
        }

        // Anchor the 45 s aiding withhold deterministically: either an explicit anchor in
        // recorded seconds (TRIAL_OUTAGE_ANCHOR_S) or the first recorded forced-outage start.
        val firstNanos = rows.first().monotonicNanos
        val anchorSeconds = System.getenv("TRIAL_OUTAGE_ANCHOR_S")?.toDoubleOrNull()
        val outageStartNanos = when {
            anchorSeconds != null -> firstNanos + (anchorSeconds * 1e9).toLong()
            else -> rows.first { it.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0 }.monotonicNanos
        }
        val outageEndNanos = outageStartNanos + OUTAGE_NANOS

        // Local ENU frame: WGS84 geodetic -> ECEF -> ENU at the first valid GNSS reference.
        val referenceRow = rows.first { it.gnssMonotonicNanos > 0 && it.gnssPosition.x.isFinite() }
        val refLatRad = Math.toRadians(referenceRow.gnssPosition.x)
        val refLonRad = Math.toRadians(referenceRow.gnssPosition.y)
        val refEcef = geodeticToEcef(referenceRow.gnssPosition.x, referenceRow.gnssPosition.y, referenceRow.gnssPosition.z)

        fun enu(latDeg: Double, lonDeg: Double, altM: Double): DoubleArray {
            val ecef = geodeticToEcef(latDeg, lonDeg, altM)
            val dx = ecef[0] - refEcef[0]
            val dy = ecef[1] - refEcef[1]
            val dz = ecef[2] - refEcef[2]
            val sinLat = sin(refLatRad); val cosLat = cos(refLatRad)
            val sinLon = sin(refLonRad); val cosLon = cos(refLonRad)
            val east = -sinLon * dx + cosLon * dy
            val north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz
            return doubleArrayOf(east, north, 0.0)
        }

        val smoothingWindow = System.getenv("TRIAL_SMOOTH")?.toIntOrNull()
            ?: com.example.phoneins.navigation.NavigationEngine.IMU_SMOOTHING_WINDOW_SAMPLES
        val engine = NavigationEngine(rows.first().motionProfile, smoothingWindow)
        val feed = CsvReplayFeedState()
        var initializationDescription: String? = null

        var maxSeparation = 0.0
        var endpointSeparation = Double.NaN
        var maxDirectionError = 0.0
        var directionSamples = 0
        var directionViolations = 0
        var zeroVelocityViolations = 0
        var outageEpochs = 0
        val reacquisitionSeparations = ArrayList<Double>()
        var reacquisitionApplied = false

        val reportDirectory = File("build/reports/trial-outage/${sourceFile.nameWithoutExtension}-${"%.3f".format((outageStartNanos - firstNanos) / 1e9)}")
            .apply { mkdirs() }
        val epochsFile = File(reportDirectory, "verification_epochs.csv")
        val summaryFile = File(reportDirectory, "verification_summary.txt")
        var previousAidingEnabled: Boolean? = null

        epochsFile.printWriter().use { out ->
            out.println("t_s,ins_lat,ins_lon,ins_alt,ins_vn,ins_ve,ins_vd,ins_speed,roll_deg,pitch_deg,yaw_deg,gns_lat,gns_lon,gns_vn,gns_ve,gns_speed,aiding,separation_m,direction_err_deg,abx,aby,abz,gbx,gby,gbz,flags")
            for (row in rows) {
                val t = (row.monotonicNanos - firstNanos) / 1e9
                val inOutage = row.monotonicNanos in outageStartNanos until outageEndNanos
                val aidingEnabled = !inOutage
                if (previousAidingEnabled != aidingEnabled) {
                    engine.setGnssAidingEnabled(aidingEnabled)
                    previousAidingEnabled = aidingEnabled
                }

                // Mirror NavigationService.processCsvReplayRow exactly.
                var imu = row.takeIf {
                    it.imuMonotonicNanos > 0L && it.imuMonotonicNanos != feed.lastImuNanos &&
                        it.acceleration.isFiniteVec() && it.angularRate.isFiniteVec()
                }?.let { TimedImuSample(it.imuMonotonicNanos, it.acceleration, it.angularRate, fresh = it.imuFresh, clipped = it.imuClipped) }
                val gnss = row.takeIf {
                    it.gnssMonotonicNanos > 0L && it.gnssMonotonicNanos != feed.lastGnssNanos &&
                        it.gnssPosition.x.isFinite() && it.gnssPosition.y.isFinite()
                }?.let {
                    GnssFix(
                        monotonicNanos = it.gnssMonotonicNanos,
                        unixMillis = it.gnssUnixMillis,
                        latitudeDeg = it.gnssPosition.x,
                        longitudeDeg = it.gnssPosition.y,
                        altitudeEllipsoidM = it.gnssPosition.z,
                        velocityNedMps = it.gnssVelocityNed,
                        horizontalAccuracyM = it.gnssHorizontalAccuracy,
                        verticalAccuracyM = it.gnssVerticalAccuracy,
                        speedAccuracyMps = it.gnssSpeedAccuracy,
                        bearingAccuracyDeg = it.gnssBearingAccuracy,
                        satellitesUsed = it.gnssSatellites,
                        hasAltitude = it.gnssHasAltitude,
                        hasHorizontalVelocity = it.gnssHasHorizontalVelocity,
                        hasDerivedDownVelocity = it.gnssHasDerivedDownVelocity,
                        isMock = it.gnssIsMock,
                    )
                }

                if (!feed.initialized && row.initialized) {
                    engine.initializeReplay(
                        ReplayInitialization(
                            latitudeDeg = row.insPosition.x,
                            longitudeDeg = row.insPosition.y,
                            altitudeEllipsoidM = row.insPosition.z,
                            velocityNedMps = row.insVelocityNed,
                            eulerDeg = row.insEulerDeg,
                            accelerometerBiasMps2 = row.accelerometerBias,
                            gyroBiasRadps = row.gyroBias,
                            motionProfile = row.motionProfile,
                            imuSample = imu ?: feed.latestImu,
                        ),
                    )
                    feed.initialized = true
                    initializationDescription = (
                        "Replay warm-start at t=%.2f s: lat=%.7f, lon=%.7f, alt=%.2f m, vel=[%.2f %.2f %.2f] m/s, " +
                            "euler=[%.1f %.1f %.1f] deg, accelBias=[%.4f %.4f %.4f] m/s^2, gyroBias=[%.5f %.5f %.5f] rad/s"
                        ).format(
                            t, row.insPosition.x, row.insPosition.y, row.insPosition.z,
                            row.insVelocityNed.x, row.insVelocityNed.y, row.insVelocityNed.z,
                            row.insEulerDeg.x, row.insEulerDeg.y, row.insEulerDeg.z,
                            row.accelerometerBias.x, row.accelerometerBias.y, row.accelerometerBias.z,
                            row.gyroBias.x, row.gyroBias.y, row.gyroBias.z,
                        )
                    imu?.let { feed.lastImuNanos = it.monotonicNanos }
                    imu = null
                }

                fun applyImu(sample: TimedImuSample) {
                    engine.processImu(sample)
                    feed.lastImuNanos = sample.monotonicNanos
                    feed.latestImu = sample
                }
                fun applyGnss(fix: GnssFix) {
                    engine.processGnss(fix)
                    feed.lastGnssNanos = fix.monotonicNanos
                    feed.latestGnss = fix
                }
                when {
                    imu != null && gnss != null && imu.monotonicNanos <= gnss.monotonicNanos -> { applyImu(imu); applyGnss(gnss) }
                    imu != null && gnss != null -> { applyGnss(gnss); applyImu(imu) }
                    imu != null -> applyImu(imu)
                    gnss != null -> applyGnss(gnss)
                }

                if (!feed.initialized) continue
                val solution = engine.snapshot(row.monotonicNanos, row.unixMillis)
                if (!solution.initialized) continue

                var separation = Double.NaN
                var directionError = Double.NaN
                var gnssSpeed = Double.NaN
                if (gnss != null) {
                    val insEnu = enu(solution.latitudeDeg, solution.longitudeDeg, solution.altitudeEllipsoidM)
                    val gnssEnu = enu(gnss.latitudeDeg, gnss.longitudeDeg, gnss.altitudeEllipsoidM)
                    separation = hypot(insEnu[0] - gnssEnu[0], insEnu[1] - gnssEnu[1])
                    gnssSpeed = hypot(gnss.velocityNedMps.x, gnss.velocityNedMps.y)
                    val insSpeed = hypot(solution.velocityNedMps.x, solution.velocityNedMps.y)
                    if (inOutage && gnssSpeed >= 1.0) {
                        directionSamples++
                        if (!insSpeed.isFinite() || insSpeed < 1e-6) {
                            zeroVelocityViolations++
                            directionError = 180.0
                        } else {
                            val insAngle = Math.toDegrees(Math.atan2(solution.velocityNedMps.y, solution.velocityNedMps.x))
                            val gnssAngle = Math.toDegrees(Math.atan2(gnss.velocityNedMps.y, gnss.velocityNedMps.x))
                            var diff = abs(insAngle - gnssAngle) % 360.0
                            if (diff > 180.0) diff = 360.0 - diff
                            directionError = diff
                            if (diff > 30.0) directionViolations++
                            if (diff > maxDirectionError) maxDirectionError = diff
                        }
                    }
                    if (inOutage) {
                        outageEpochs++
                        if (separation > maxSeparation) maxSeparation = separation
                        endpointSeparation = separation
                    } else if (row.monotonicNanos >= outageEndNanos && row.monotonicNanos < outageEndNanos + 10_000_000_000L) {
                        reacquisitionSeparations.add(separation)
                        if (!reacquisitionApplied && solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0) {
                            reacquisitionApplied = true
                        }
                    }
                }
                out.println(
                    "%.3f,%.7f,%.7f,%.2f,%.3f,%.3f,%.3f,%.3f,%.1f,%.1f,%.1f,%.7f,%.7f,%.3f,%.3f,%.2f,%d,%.2f,%.1f,%.4f,%.4f,%.4f,%.5f,%.5f,%.5f,%d".format(
                        t,
                        solution.latitudeDeg, solution.longitudeDeg, solution.altitudeEllipsoidM,
                        solution.velocityNedMps.x, solution.velocityNedMps.y, solution.velocityNedMps.z,
                        hypot(solution.velocityNedMps.x, solution.velocityNedMps.y),
                        solution.eulerDeg.x, solution.eulerDeg.y, solution.eulerDeg.z,
                        if (gnss != null) gnss.latitudeDeg else Double.NaN,
                        if (gnss != null) gnss.longitudeDeg else Double.NaN,
                        if (gnss != null) gnss.velocityNedMps.x else Double.NaN,
                        if (gnss != null) gnss.velocityNedMps.y else Double.NaN,
                        gnssSpeed,
                        if (aidingEnabled) 1 else 0,
                        separation, directionError,
                        solution.accelerometerBiasMps2.x, solution.accelerometerBiasMps2.y, solution.accelerometerBiasMps2.z,
                        solution.gyroBiasRadps.x, solution.gyroBiasRadps.y, solution.gyroBiasRadps.z,
                        solution.qualityFlags,
                    ),
                )
            }
        }

        summaryFile.writeText(
            buildString {
                appendLine("source=${sourceFile.name}")
                val digest = MessageDigest.getInstance("SHA-256")
                sourceFile.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                appendLine("source_sha256=${digest.digest().joinToString("") { "%02x".format(it) }}")
                appendLine("rows=${rows.size}")
                appendLine("initialization=$initializationDescription")
                appendLine("outage_start_t_s=%.3f".format((outageStartNanos - firstNanos) / 1e9))
                appendLine("outage_duration_s=45.000")
                appendLine("outage_epochs_evaluated=$outageEpochs")
                appendLine("max_separation_m=%.2f".format(maxSeparation))
                appendLine("endpoint_separation_m=%.2f".format(endpointSeparation))
                appendLine("max_direction_error_deg=%.1f".format(maxDirectionError))
                appendLine("direction_samples=$directionSamples")
                appendLine("direction_violations_over_30deg=$directionViolations")
                appendLine("zero_velocity_violations=$zeroVelocityViolations")
                appendLine("reacquisition_aiding_applied=$reacquisitionApplied")
                appendLine("reacquisition_max_separation_first_10s=%.2f".format(reacquisitionSeparations.maxOrNull() ?: Double.NaN))
                appendLine("separation_limit_m=150")
                appendLine("direction_limit_deg=30")
                val completeCoverage = outageEpochs > 0 && endpointSeparation.isFinite() &&
                    rows.last().monotonicNanos >= outageEndNanos
                val passed = completeCoverage && maxSeparation <= 150.0 && directionSamples > 0 &&
                    directionViolations == 0 && zeroVelocityViolations == 0 && reacquisitionApplied
                appendLine("complete_reference_coverage=$completeCoverage")
                appendLine("verdict=${if (passed) "PASS" else if (!completeCoverage || directionSamples == 0) "INCONCLUSIVE" else "FAIL"}")
            },
        )
        assertTrue("Incomplete 45-second reference coverage; see ${summaryFile.absolutePath}",
            outageEpochs > 0 && endpointSeparation.isFinite() && rows.last().monotonicNanos >= outageEndNanos)
        assertTrue("No moving GNSS reference epochs within outage; see ${summaryFile.absolutePath}", directionSamples > 0)
        assertTrue("Maximum horizontal separation $maxSeparation m exceeds 150 m; see ${summaryFile.absolutePath}", maxSeparation <= 150.0)
        assertTrue("Direction violations=$directionViolations, max=$maxDirectionError deg; see ${summaryFile.absolutePath}",
            directionViolations == 0 && zeroVelocityViolations == 0)
        assertTrue("GNSS correction did not resume after outage; see ${summaryFile.absolutePath}", reacquisitionApplied)
    }

    /** WGS84 geodetic coordinates to Earth-Centered-Earth-Fixed metres. */
    private fun geodeticToEcef(latitudeDeg: Double, longitudeDeg: Double, altitudeM: Double): DoubleArray {
        val a = 6378137.0
        val f = 1.0 / 298.257223563
        val e2 = f * (2.0 - f)
        val lat = Math.toRadians(latitudeDeg)
        val lon = Math.toRadians(longitudeDeg)
        val sinLat = sin(lat); val cosLat = cos(lat)
        val n = a / sqrt(1.0 - e2 * sinLat * sinLat)
        return doubleArrayOf(
            (n + altitudeM) * cosLat * cos(lon),
            (n + altitudeM) * cosLat * sin(lon),
            (n * (1.0 - e2) + altitudeM) * sinLat,
        )
    }

    private fun Vec3.isFiniteVec() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        private val OUTAGE_NANOS = 45L * 1_000_000_000L
    }
}
