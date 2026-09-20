package com.example.phoneins.navigation

import kotlin.test.assertEquals
import kotlin.test.Test

class BiasProcessNoiseTest {
    @Test fun biasVarianceGrowsByConfiguredAmplitudeSquaredTimesDt() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0))
        val covarianceField = NavigationEngine::class.java.getDeclaredField("covariance").apply { isAccessible = true }
        fun covariance() = (covarianceField.get(engine) as DoubleArray).copyOf()
        val sample = TimedImuSample(1_000_000_000, Vec3(0.0, 0.0, -9.81), Vec3.ZERO)
        engine.processImu(sample)
        val before = covariance()
        engine.processImu(sample.copy(monotonicNanos = 1_010_000_000))
        val after = covariance()
        for (axis in 0..2) {
            assertEquals(0.002 * 0.002 * 0.01, after[(9 + axis) * 15 + 9 + axis] - before[(9 + axis) * 15 + 9 + axis], 1e-12)
            assertEquals(0.0002 * 0.0002 * 0.01, after[(12 + axis) * 15 + 12 + axis] - before[(12 + axis) * 15 + 12 + axis], 1e-12)
        }
        assertEquals(0.004, NavigationEngine.ATTITUDE_PROCESS_NOISE_RAD_PER_SQRT_SECOND, 0.0)
    }
}
