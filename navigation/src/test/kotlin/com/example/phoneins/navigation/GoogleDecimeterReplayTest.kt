package com.example.phoneins.navigation

import java.io.File
import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays a public Pixel 4 fixture extracted from Google's 2021 Smartphone
 * Decimeter Challenge. Source mirror: Stanford-NavLab/gnss_lib_py.
 */
class GoogleDecimeterReplayTest {
    data class Truth(val unixMillis: Long, val latitude: Double, val longitude: Double, val altitude: Double, val course: Double)
    data class Event(val unixMillis: Long, val kind: String, val values: List<String>)

    @Test
    fun publicPixel4LogStaysWithinTenMetresWhenGnssIsValid() {
        val log = resource("google_decimeter_2021/Pixel4_GnssLog.txt").readLines()
        val truth = resource("google_decimeter_2021/Pixel4_ground_truth.csv").readLines().drop(1).map { line ->
            val f = line.split(',')
            val gpsMillis = f[2].toLong()
            Truth(gpsMillis + UNIX_GPS_EPOCH_OFFSET_MS - GPS_UTC_LEAP_MS, f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[10].toDouble())
        }
        val events = log.asSequence().filter { !it.startsWith('#') && it.isNotBlank() }.mapNotNull { line ->
            val f = line.split(',')
            when (f[0]) {
                "Accel", "Gyro", "Mag" -> Event(f[1].toLong(), f[0], f)
                "Fix" -> if (f[1] == "GPS") Event(f[8].toLong(), f[0], f) else null
                else -> null
            }
        }.sortedBy { it.unixMillis }.toList()

        val engine = NavigationEngine(MotionProfile.VEHICLE)
        val firstTruth = truth.first()
        engine.initializeManual(ManualInitialization(firstTruth.latitude, firstTruth.longitude, firstTruth.altitude, 0.0, 0.0, firstTruth.course))
        val firstSensor = events.first { it.kind == "Accel" || it.kind == "Gyro" }
        val firstMonotonic = firstSensor.values[2].toLong()
        var latestAcceleration = Vec3(0.0, 0.0, -9.80665)
        var imuCount = 0
        var gnssCount = 0
        val errors = mutableListOf<Double>()

        for (event in events) {
            when (event.kind) {
                "Accel" -> {
                    val native = Vec3(event.values[3].toDouble(), event.values[4].toDouble(), event.values[5].toDouble())
                    latestAcceleration = Vec3(native.y, native.x, -native.z)
                }
                "Gyro" -> {
                    val native = Vec3(event.values[3].toDouble(), event.values[4].toDouble(), event.values[5].toDouble())
                    val body = Vec3(native.y, native.x, -native.z)
                    engine.processImu(TimedImuSample(event.values[2].toLong(), latestAcceleration, body))
                    imuCount++
                }
                "Fix" -> {
                    val f = event.values
                    val speed = f[5].toDouble()
                    val bearing = Math.toRadians(f[7].toDouble())
                    val monotonic = firstMonotonic + (event.unixMillis - firstSensor.unixMillis) * 1_000_000
                    val fix = GnssFix(
                        monotonicNanos = monotonic,
                        unixMillis = event.unixMillis,
                        latitudeDeg = f[2].toDouble(),
                        longitudeDeg = f[3].toDouble(),
                        altitudeEllipsoidM = f[4].toDouble(),
                        velocityNedMps = Vec3(speed * cos(bearing), speed * sin(bearing), Double.NaN),
                        horizontalAccuracyM = f[6].toDouble(),
                        verticalAccuracyM = 50.0,
                        speedAccuracyMps = f[9].toDouble().coerceAtLeast(0.1),
                        bearingAccuracyDeg = f[10].toDouble().takeIf { it > 0 } ?: 180.0,
                        satellitesUsed = 0,
                        hasHorizontalVelocity = speed > 0.05,
                    )
                    engine.processGnss(fix)
                    val solution = engine.snapshot(monotonic, event.unixMillis)
                    val nearest = truth.minBy { abs(it.unixMillis - event.unixMillis) }
                    errors += horizontalDistanceM(solution.latitudeDeg, solution.longitudeDeg, nearest.latitude, nearest.longitude)
                    gnssCount++
                }
            }
        }

        assertTrue(imuCount >= 20, "Expected the public raw IMU samples to be replayed")
        assertEquals(2, gnssCount)
        assertTrue(errors.isNotEmpty())
        val lastEventMillis = events.maxOf { it.unixMillis }
        val outageSnapshot = engine.snapshot(
            firstMonotonic + (lastEventMillis - firstSensor.unixMillis) * 1_000_000 + 3_000_000_000,
            lastEventMillis + 3_000,
        )
        assertTrue(outageSnapshot.degraded, "GNSS outage must visibly enter degraded mode")
        assertTrue(outageSnapshot.qualityFlags and QualityFlag.DEAD_RECKONING != 0, "GNSS outage must be flagged as dead reckoning")
        val sorted = errors.sorted()
        val report = buildString {
            appendLine("Google Smartphone Decimeter 2021 · Pixel 4 public fixture")
            appendLine("Raw gyro samples replayed: $imuCount")
            appendLine("Valid GPS fixes scored: $gnssCount")
            appendLine("Median horizontal error: ${"%.3f".format(sorted[sorted.size / 2])} m")
            appendLine("Maximum horizontal error: ${"%.3f".format(sorted.last())} m")
            appendLine("Acceptance threshold: < 10.000 m for every valid GPS fix")
            appendLine("Post-fix outage: DEGRADED + DEAD_RECKONING (drift intentionally not acceptance-scored)")
            appendLine("Result: ${if (sorted.last() < 10.0) "PASS" else "FAIL"}")
        }
        File("build/reports/google-decimeter-replay.txt").apply { parentFile.mkdirs(); writeText(report) }
        println(report)
        assertTrue(sorted.last() < 10.0, report)
    }

    private fun resource(path: String) = checkNotNull(javaClass.classLoader.getResource(path)) { "Missing $path" }.let { File(it.toURI()) }

    private fun horizontalDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 6_371_008.8 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        private const val UNIX_GPS_EPOCH_OFFSET_MS = 315_964_800_000L
        private const val GPS_UTC_LEAP_MS = 18_000L
    }
}
