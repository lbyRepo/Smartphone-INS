package com.example.phoneins.navigation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic replay adapter for the bundled Google Smartphone Decimeter Challenge ground-truth
 * segment. Route position, speed and course are the published Pixel 4 reference values. The IMU is
 * synthesized for a screen-up, forward-aligned, rigidly mounted phone because the small public
 * GnssLogger fixture bundled by gnss_lib_py contains only 22 gyro samples.
 */
object DecimeterRouteScenario {
    const val OUTAGE_START_INDEX = 105
    const val OUTAGE_END_INDEX = 125
    private const val UNIX_GPS_EPOCH_OFFSET_MS = 315_964_800_000L
    private const val GPS_UTC_LEAP_MS = 18_000L
    private const val STANDARD_GRAVITY = 9.80665

    data class Point(
        val gpsMillis: Long,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val altitudeM: Double,
        val speedMps: Double,
        val courseDeg: Double,
    ) {
        val unixMillis: Long get() = gpsMillis + UNIX_GPS_EPOCH_OFFSET_MS - GPS_UTC_LEAP_MS
    }

    fun parseGroundTruth(lines: Sequence<String>): List<Point> = lines
        .filter { it.isNotBlank() }
        .drop(1)
        .map { line ->
            val values = line.split(',')
            Point(
                gpsMillis = values[2].toLong(),
                latitudeDeg = values[3].toDouble(),
                longitudeDeg = values[4].toDouble(),
                altitudeM = values[5].toDouble(),
                speedMps = values[9].toDouble(),
                courseDeg = values[10].toDouble(),
            )
        }
        .toList()

    fun monotonicNanos(point: Point, first: Point, startNanos: Long): Long =
        startNanos + (point.gpsMillis - first.gpsMillis) * 1_000_000L

    fun gnssFix(point: Point, first: Point, startNanos: Long): GnssFix {
        val course = Math.toRadians(point.courseDeg)
        val moving = point.speedMps >= 0.5
        return GnssFix(
            monotonicNanos = monotonicNanos(point, first, startNanos),
            unixMillis = point.unixMillis,
            latitudeDeg = point.latitudeDeg,
            longitudeDeg = point.longitudeDeg,
            altitudeEllipsoidM = point.altitudeM,
            velocityNedMps = Vec3(point.speedMps * cos(course), point.speedMps * sin(course), 0.0),
            horizontalAccuracyM = 2.5,
            verticalAccuracyM = 4.0,
            speedAccuracyMps = 0.2,
            bearingAccuracyDeg = if (moving) 4.0 else 180.0,
            satellitesUsed = 18,
            hasAltitude = true,
            hasHorizontalVelocity = true,
            hasDerivedDownVelocity = true,
            isMock = true,
        )
    }

    fun imuBetween(previous: Point, next: Point, first: Point, startNanos: Long): List<TimedImuSample> {
        val dt = ((next.gpsMillis - previous.gpsMillis) * 1e-3).coerceAtLeast(0.01)
        val samples = (dt * 100.0).toInt().coerceAtLeast(1)
        val previousCourse = Math.toRadians(previous.courseDeg)
        val nextCourse = Math.toRadians(next.courseDeg)
        val courseChange = wrapRadians(nextCourse - previousCourse)
        val yawRate = if (previous.speedMps > 0.5 || next.speedMps > 0.5) courseChange / dt else 0.0
        val previousNorth = previous.speedMps * cos(previousCourse)
        val previousEast = previous.speedMps * sin(previousCourse)
        val nextNorth = next.speedMps * cos(nextCourse)
        val nextEast = next.speedMps * sin(nextCourse)
        val accelerationNorth = (nextNorth - previousNorth) / dt
        val accelerationEast = (nextEast - previousEast) / dt

        return List(samples) { index ->
            val fraction = (index + 1.0) / samples
            val course = previousCourse + courseChange * fraction
            val forwardAcceleration = accelerationNorth * cos(course) + accelerationEast * sin(course)
            val rightAcceleration = -accelerationNorth * sin(course) + accelerationEast * cos(course)
            val seconds = (previous.gpsMillis - first.gpsMillis) * 1e-3 + fraction * dt
            TimedImuSample(
                monotonicNanos = monotonicNanos(previous, first, startNanos) + ((index + 1.0) * dt / samples * 1e9).toLong(),
                accelerationMps2 = Vec3(
                    forwardAcceleration + 0.10 + 0.015 * sin(seconds * 0.7),
                    rightAcceleration - 0.07 + 0.012 * cos(seconds * 0.5),
                    -STANDARD_GRAVITY + 0.04 + 0.01 * sin(seconds * 0.3),
                ),
                angularRateRadps = Vec3(
                    0.0025 + 0.0005 * sin(seconds * 0.9),
                    -0.0018 + 0.0004 * cos(seconds * 0.8),
                    yawRate + 0.0028 + 0.0005 * sin(seconds * 1.1),
                ),
            )
        }
    }

    private fun wrapRadians(value: Double): Double {
        var wrapped = value
        while (wrapped > PI) wrapped -= 2 * PI
        while (wrapped < -PI) wrapped += 2 * PI
        return wrapped
    }
}
