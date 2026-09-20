package com.example.phoneins.navigation

import kotlin.math.abs
import kotlin.math.sqrt

/** Protect slow bias learning from transient motion/data loss; never filters or clips raw IMU. */
internal class ImuQualityMonitor {
    private val window = ArrayDeque<TimedImuSample>()
    private var last: TimedImuSample? = null
    private var cleanSince: Long? = null

    fun reset() { window.clear(); last = null; cleanSince = null }

    fun observe(sample: TimedImuSample) {
        val previous = last
        if (previous != null && sample.monotonicNanos <= previous.monotonicNanos) {
            cleanSince = null
            window.clear()
            return
        }
        last = sample
        val valid = sample.fresh && !sample.clipped &&
            sample.accelerationMps2.toArray().all(Double::isFinite) && sample.angularRateRadps.toArray().all(Double::isFinite)
        val gap = previous?.let { sample.monotonicNanos - it.monotonicNanos > MAX_GAP_NS } == true
        if (!valid || gap) { cleanSince = null; window.clear() }
        if (!valid) return
        window.addLast(sample)
        while (window.isNotEmpty() && sample.monotonicNanos - window.first().monotonicNanos > WINDOW_NS) window.removeFirst()
        val abrupt = previous?.let {
            (sample.accelerationMps2 - it.accelerationMps2).norm() > 2.0 ||
                (sample.angularRateRadps - it.angularRateRadps).norm() > 0.15
        } == true
        val dynamic = abs(sample.accelerationMps2.norm() - 9.80665) > 1.0 || sample.angularRateRadps.norm() > 0.35
        if (abrupt || dynamic || window.size < 20 || sample.monotonicNanos - window.first().monotonicNanos < 400_000_000L) {
            cleanSince = null
            return
        }
        val meanA = window.fold(Vec3.ZERO) { sum, s -> sum + s.accelerationMps2 } / window.size.toDouble()
        val meanG = window.fold(Vec3.ZERO) { sum, s -> sum + s.angularRateRadps } / window.size.toDouble()
        val rmsA = sqrt(window.sumOf { val d = (it.accelerationMps2 - meanA).norm(); d * d } / window.size)
        val rmsG = sqrt(window.sumOf { val d = (it.angularRateRadps - meanG).norm(); d * d } / window.size)
        if (rmsA > 0.8 || rmsG > 0.08) cleanSince = null
        else if (cleanSince == null) cleanSince = sample.monotonicNanos
    }

    fun canLearn(timeNanos: Long): Boolean {
        val sample = last ?: return false
        val since = cleanSince ?: return false
        // Also prevents delayed GNSS fixes from teaching biases after a rough interval.
        return abs(timeNanos - sample.monotonicNanos) <= MAX_MEASUREMENT_AGE_NS &&
            timeNanos >= since && timeNanos - since >= RECOVERY_NS
    }

    companion object {
        const val WINDOW_NS = 500_000_000L
        const val RECOVERY_NS = 2_000_000_000L
        const val MAX_GAP_NS = 50_000_000L
        const val MAX_MEASUREMENT_AGE_NS = 100_000_000L
    }
}
