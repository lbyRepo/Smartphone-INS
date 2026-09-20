package com.example.phoneins.service

import com.example.phoneins.navigation.*
import kotlin.math.*

/** Debug scenario: stays at rest until the operator explicitly starts motion. */
class DeterministicSimulator(private val startNanos: Long, private val startMillis: Long) {
    private var motionStartNanos: Long? = null
    private var lastGnssSecond = -1L
    @Synchronized fun startMotion(nowNanos: Long) {
        if (motionStartNanos == null) motionStartNanos = nowNanos
    }

    data class Tick(val imu: TimedImuSample, val mag: MagSample?, val gnss: GnssFix?)

    @Synchronized fun tick(nowNanos: Long): Tick {
        val elapsed = (nowNanos - startNanos) * 1e-9
        val t = motionStartNanos?.let { ((nowNanos - it) * 1e-9).coerceAtLeast(0.0) }
        val speed = if (t == null) 0.0 else (2.0 * t).coerceAtMost(8.0)
        val turnRate = Math.toRadians(8.0)
        val turnTime = if (t == null) 0.0 else (t - 8.0).coerceIn(0.0, 8.0)
        val yaw = turnTime * turnRate
        val yawRate = if (t != null && t >= 8.0 && t < 16.0) turnRate else 0.0
        val forwardAcceleration = if (t != null && t < 4.0) 2.0 else 0.0
        // Integrals of the SAME velocity used by GNSS; continuous through the turn.
        val north = when {
            t == null -> 0.0
            t < 4.0 -> t * t
            t < 8.0 -> 16.0 + 8.0 * (t - 4.0)
            else -> 48.0 + 8.0 / turnRate * sin(yaw) + 8.0 * max(0.0, t - 16.0) * cos(yaw)
        }
        val east = if (t == null || t < 8.0) 0.0 else
            8.0 / turnRate * (1.0 - cos(yaw)) + 8.0 * max(0.0, t - 16.0) * sin(yaw)
        val imu = TimedImuSample(nowNanos,
            Vec3(forwardAcceleration, speed * yawRate, -9.80665),
            Vec3(0.0004, -0.0003, yawRate + 0.0008))
        val disturbance = if (t != null && t > 6.0) 80.0 * sin(t * 3.0) else 0.0
        val mag = MagSample(nowNanos, Vec3(31.0 * cos(yaw), -31.0 * sin(yaw) + disturbance, 34.0))
        val second = floor(elapsed).toLong()
        val gnss = if (second != lastGnssSecond) {
            lastGnssSecond = second
            // Local WGS-84 curvature, consistent with NED velocity for this short route.
            val latitude = Math.toRadians(1.3521)
            val e2 = 6.69437999014e-3
            val radiusN = 6378137.0 / sqrt(1.0 - e2 * sin(latitude).pow(2))
            val radiusM = 6378137.0 * (1.0 - e2) / (1.0 - e2 * sin(latitude).pow(2)).pow(1.5)
            GnssFix(nowNanos, startMillis + (elapsed * 1000).toLong(),
                1.3521 + Math.toDegrees(north / (radiusM + 25.0)),
                103.8198 + Math.toDegrees(east / ((radiusN + 25.0) * cos(latitude))),
                25.0, Vec3(speed * cos(yaw), speed * sin(yaw), 0.0),
                horizontalAccuracyM = 2.2, verticalAccuracyM = 4.0, speedAccuracyMps = 0.18,
                bearingAccuracyDeg = if (speed > 2) 4.0 else 30.0, satellitesUsed = 18,
                hasDerivedDownVelocity = true, isMock = true)
        } else null
        return Tick(imu, mag, gnss)
    }
}
