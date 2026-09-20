package com.example.phoneins.navigation

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Protects slow bias learning from transient motion and data loss; never filters or clips raw IMU.
 *
 * Two qualifications are provided:
 * - [canLearn] is the strict stillness/clean gate used for stationary calibration and for full
 *   bias gains.
 * - [biasAdaptationAllowed] is a long-window qualification that permits *slow* bias adaptation
 *   during physically plausible rough motion (real road/handlebar vibration), while still
 *   rejecting sample-rate aliasing and corrupted data. Without it, real bicycle/van logs held
 *   learning 0-16 % of the time, so biases never adapted at all.
 */
internal class ImuQualityMonitor {
    private val window = ArrayDeque<TimedImuSample>()
    private val adaptationWindow = ArrayDeque<TimedImuSample>()
    private var last: TimedImuSample? = null
    private var cleanSince: Long? = null
    private var adaptationSince: Long? = null
    private var samplesSinceAdaptationCheck = 0
    private var adaptationPlausible = false

    fun reset() {
        window.clear()
        adaptationWindow.clear()
        last = null
        cleanSince = null
        adaptationSince = null
        samplesSinceAdaptationCheck = 0
        adaptationPlausible = false
    }

    fun observe(sample: TimedImuSample) {
        val previous = last
        if (previous != null && sample.monotonicNanos <= previous.monotonicNanos) {
            cleanSince = null
            adaptationSince = null
            window.clear()
            adaptationWindow.clear()
            return
        }
        last = sample
        val valid = sample.fresh && !sample.clipped &&
            sample.accelerationMps2.toArray().all(Double::isFinite) && sample.angularRateRadps.toArray().all(Double::isFinite)
        val gap = previous?.let { sample.monotonicNanos - it.monotonicNanos > MAX_GAP_NS } == true
        if (!valid || gap) {
            cleanSince = null
            adaptationSince = null
            adaptationWindow.clear()
            adaptationPlausible = false
            window.clear()
        }
        if (!valid) return
        observeAdaptation(sample)
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

    /** Long-window plausibility check; cheap because it runs at [ADAPTATION_CHECK_EVERY_SAMPLES]. */
    private fun observeAdaptation(sample: TimedImuSample) {
        adaptationWindow.addLast(sample)
        while (adaptationWindow.isNotEmpty() &&
            sample.monotonicNanos - adaptationWindow.first().monotonicNanos > ADAPTATION_WINDOW_NS
        ) {
            adaptationWindow.removeFirst()
        }
        if (++samplesSinceAdaptationCheck < ADAPTATION_CHECK_EVERY_SAMPLES) return
        samplesSinceAdaptationCheck = 0
        val span = adaptationWindow.last().monotonicNanos - adaptationWindow.first().monotonicNanos
        if (adaptationWindow.size < ADAPTATION_MIN_SAMPLES || span < ADAPTATION_SPAN_NS) {
            adaptationPlausible = false
            adaptationSince = null
            return
        }
        val count = adaptationWindow.size
        val meanA = adaptationWindow.fold(Vec3.ZERO) { sum, s -> sum + s.accelerationMps2 } / count.toDouble()
        val meanG = adaptationWindow.fold(Vec3.ZERO) { sum, s -> sum + s.angularRateRadps } / count.toDouble()
        val rmsA = sqrt(adaptationWindow.sumOf { val d = (it.accelerationMps2 - meanA).norm(); d * d } / count)
        val rmsG = sqrt(adaptationWindow.sumOf { val d = (it.angularRateRadps - meanG).norm(); d * d } / count)
        var previous: TimedImuSample? = null
        var jumps = 0
        var largeJumps = 0
        var previousResidual = 0.0
        var residualProduct = 0.0
        var residualEnergy = 0.0
        var first = true
        for (s in adaptationWindow) {
            previous?.let {
                jumps++
                if ((s.accelerationMps2 - it.accelerationMps2).norm() > ADAPTATION_LARGE_JUMP_MPS2) largeJumps++
            }
            // Lag-1 autocorrelation of the body-X acceleration residual: physical vibration is
            // correlated sample to sample, while sample-rate aliasing alternates (negative).
            val residual = s.accelerationMps2.x - meanA.x
            if (!first) residualProduct += residual * previousResidual
            residualEnergy += residual * residual
            previousResidual = residual
            first = false
            previous = s
        }
        val largeJumpFraction = if (jumps == 0) 0.0 else largeJumps.toDouble() / jumps
        val autocorrelation = if (residualEnergy <= 0.0) 0.0 else residualProduct / residualEnergy
        adaptationPlausible = rmsA <= ADAPTATION_MAX_RMS_ACCEL_MPS2 &&
            rmsG <= ADAPTATION_MAX_RMS_RATE_RADPS &&
            largeJumpFraction < ADAPTATION_MAX_LARGE_JUMP_FRACTION &&
            autocorrelation >= ADAPTATION_MIN_AUTOCORRELATION
        if (adaptationPlausible) {
            if (adaptationSince == null) adaptationSince = sample.monotonicNanos
        } else {
            adaptationSince = null
        }
    }

    /**
     * True while the short window shows quasi-steady, non-cornering motion. Accelerometer bias
     * is only separable from tilt in this condition; adapting it during cornering absorbs real
     * lateral dynamics into bias and degrades outage direction fidelity.
     */
    fun biasAdaptationSteady(timeNanos: Long): Boolean {
        val sample = last ?: return false
        if (abs(timeNanos - sample.monotonicNanos) > MAX_MEASUREMENT_AGE_NS) return false
        if (window.size < STEADY_MIN_SAMPLES) return false
        if (sample.monotonicNanos - window.first().monotonicNanos < STEADY_SPAN_NS) return false
        val count = window.size.toDouble()
        val meanA = window.fold(Vec3.ZERO) { sum, s -> sum + s.accelerationMps2 } / count
        val meanG = window.fold(Vec3.ZERO) { sum, s -> sum + s.angularRateRadps } / count
        return meanG.norm() <= STEADY_MAX_MEAN_RATE_RADPS &&
            abs(meanA.norm() - 9.80665) <= STEADY_MAX_GRAVITY_DEVIATION_MPS2
    }

    fun canLearn(timeNanos: Long): Boolean {
        val sample = last ?: return false
        val since = cleanSince ?: return false
        // Also prevents delayed GNSS fixes from teaching biases after a rough interval.
        return abs(timeNanos - sample.monotonicNanos) <= MAX_MEASUREMENT_AGE_NS &&
            timeNanos >= since && timeNanos - since >= RECOVERY_NS
    }

    /**
     * True while the last [ADAPTATION_WINDOW_NS] of IMU data is physically plausible motion
     * (not aliased or corrupted) and has been so for [ADAPTATION_RECOVERY_NS].
     */
    fun biasAdaptationAllowed(timeNanos: Long): Boolean {
        if (!adaptationPlausible) return false
        val sample = last ?: return false
        val since = adaptationSince ?: return false
        return abs(timeNanos - sample.monotonicNanos) <= MAX_MEASUREMENT_AGE_NS &&
            timeNanos >= since && timeNanos - since >= ADAPTATION_RECOVERY_NS
    }

    companion object {
        const val WINDOW_NS = 500_000_000L
        const val RECOVERY_NS = 2_000_000_000L
        const val MAX_GAP_NS = 50_000_000L
        const val MAX_MEASUREMENT_AGE_NS = 100_000_000L

        /**
         * Slow-adaptation qualification: 5 s window, checked every 5 samples (20 Hz). The RMS
         * bounds admit real bicycle/van road vibration (measured: bicycle accel RMS median 4.5,
         * p95 8.2 m/s^2) while the large-jump fraction and autocorrelation criteria exclude
         * sample-rate aliasing, which is what the strict gate must keep rejecting.
         */
        const val ADAPTATION_WINDOW_NS = 5_000_000_000L
        const val ADAPTATION_SPAN_NS = 4_500_000_000L
        const val ADAPTATION_MIN_SAMPLES = 400
        const val ADAPTATION_RECOVERY_NS = 1_000_000_000L
        const val ADAPTATION_CHECK_EVERY_SAMPLES = 5
        const val ADAPTATION_MAX_RMS_ACCEL_MPS2 = 5.0
        const val ADAPTATION_MAX_RMS_RATE_RADPS = 1.5
        const val ADAPTATION_LARGE_JUMP_MPS2 = 10.0
        const val ADAPTATION_MAX_LARGE_JUMP_FRACTION = 0.02
        const val ADAPTATION_MIN_AUTOCORRELATION = 0.0

        /**
         * Quasi-steady motion bounds used to gate accelerometer-bias adaptation; measured on the
         * 2026-09-20 bicycle log (mean |omega| median 0.24 rad/s, gravity deviation median
         * 0.24 m/s^2) these admit roughly half of the riding time while excluding cornering.
         */
        const val STEADY_MIN_SAMPLES = 25
        const val STEADY_SPAN_NS = 400_000_000L
        const val STEADY_MAX_MEAN_RATE_RADPS = 0.25
        const val STEADY_MAX_GRAVITY_DEVIATION_MPS2 = 1.5
    }
}
