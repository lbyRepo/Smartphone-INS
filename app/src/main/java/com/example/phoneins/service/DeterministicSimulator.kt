package com.example.phoneins.service

import com.example.phoneins.navigation.*
import kotlin.math.*

/** Debug-only deterministic scenario: stationary, accelerate and turn with operator-controlled GNSS aiding. */
class DeterministicSimulator(
    private val startNanos: Long,
    private val startMillis: Long,
) {
    private val startLatitude = 1.3521
    private val startLongitude = 103.8198
    private val metresPerDegreeLat = 111_132.0
    private val metresPerDegreeLon = 111_320.0 * cos(Math.toRadians(startLatitude))
    private var lastGnssSecond = -1L

    data class Tick(val imu: TimedImuSample, val mag: MagSample?, val gnss: GnssFix?)

    fun tick(nowNanos: Long): Tick {
        val t = (nowNanos - startNanos) * 1e-9
        val speed = when {
            t < 4 -> 0.0
            t < 8 -> (t - 4) * 2.0
            else -> 8.0
        }
        val yawRate = if (t in 12.0..20.0) Math.toRadians(8.0) else 0.0
        val yaw = (max(0.0, min(t, 20.0) - 12.0) * Math.toRadians(8.0))
        val accelerationForward = if (t in 4.0..8.0) 2.0 else 0.0
        val imu = TimedImuSample(
            monotonicNanos = nowNanos,
            accelerationMps2 = Vec3(accelerationForward, speed * yawRate, -9.80665),
            angularRateRadps = Vec3(0.0004, -0.0003, yawRate + 0.0008),
        )
        val magDisturbance = if (t > 10) 80.0 * sin(t * 3) else 0.0
        val mag = MagSample(nowNanos, Vec3(31.0, -4.0 + magDisturbance, 34.0))
        val second = floor(t).toLong()
        val gnss = if (second != lastGnssSecond) {
            lastGnssSecond = second
            val distance = if (t < 4) 0.0 else if (t < 8) (t - 4).pow(2) else 16.0 + (t - 8) * 8.0
            val north = distance * cos(yaw)
            val east = distance * sin(yaw)
            GnssFix(
                monotonicNanos = nowNanos,
                unixMillis = startMillis + (t * 1000).toLong(),
                latitudeDeg = startLatitude + north / metresPerDegreeLat,
                longitudeDeg = startLongitude + east / metresPerDegreeLon,
                altitudeEllipsoidM = 25.0 + 0.15 * sin(t / 4),
                velocityNedMps = Vec3(speed * cos(yaw), speed * sin(yaw), -0.04 * cos(t / 4)),
                horizontalAccuracyM = 2.2,
                verticalAccuracyM = 4.0,
                speedAccuracyMps = 0.18,
                bearingAccuracyDeg = if (speed > 2) 4.0 else 30.0,
                satellitesUsed = 18,
                hasDerivedDownVelocity = true,
                isMock = true,
            )
        } else null
        return Tick(imu, mag, gnss)
    }
}
