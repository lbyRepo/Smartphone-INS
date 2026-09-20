package com.example.phoneins.service

import com.example.phoneins.navigation.TimedImuSample
import com.example.phoneins.navigation.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** IMU-only stillness is necessary, not proof of zero translational velocity. */
internal fun staticImuMeans(samples: List<TimedImuSample>, nowNanos: Long): Pair<Vec3, Vec3> {
    require(samples.size >= 300 && samples.last().monotonicNanos - samples.first().monotonicNanos >= 2_900_000_000L) {
        "Keep still for at least 3 seconds before applying manual initialization (300 fresh IMU samples required)."
    }
    require(nowNanos - samples.last().monotonicNanos in 0L..100_000_000L &&
        samples.all { it.fresh && !it.clipped && it.accelerationMps2.toArray().all(Double::isFinite) && it.angularRateRadps.toArray().all(Double::isFinite) } &&
        samples.zipWithNext().all { (a, b) -> b.monotonicNanos - a.monotonicNanos in 1L..100_000_000L }) {
        "IMU samples are stale or incomplete. Keep still and retry."
    }
    val acceleration = samples.fold(Vec3.ZERO) { sum, s -> sum + s.accelerationMps2 } / samples.size.toDouble()
    val rate = samples.fold(Vec3.ZERO) { sum, s -> sum + s.angularRateRadps } / samples.size.toDouble()
    val accelRms = sqrt(samples.sumOf { val n = (it.accelerationMps2 - acceleration).norm(); n * n } / samples.size)
    val rateRms = sqrt(samples.sumOf { val n = (it.angularRateRadps - rate).norm(); n * n } / samples.size)
    require(abs(acceleration.norm() - 9.80665) < 0.15 && rate.norm() < 0.025 && accelRms < 0.1 && rateRms < 0.01) {
        "Motion detected. Stop moving and keep still for 3 seconds before manual initialization."
    }
    return acceleration to rate
}
