package com.example.phoneins.navigation

import kotlin.math.abs
import kotlin.test.*

class RoughRoadProtectionTest {
    private fun sample(time: Long, acceleration: Vec3 = Vec3(0.0, 0.0, -9.81), rate: Vec3 = Vec3.ZERO) =
        TimedImuSample(time, acceleration, rate)

    @Test fun detectsShockClippingTimingAndRequiresCleanRecovery() {
        fun warmed(): ImuQualityMonitor = ImuQualityMonitor().also { monitor ->
            (1..300).forEach { monitor.observe(sample(it * 10_000_000L)) }
            assertTrue(monitor.canLearn(3_000_000_000L))
        }
        for (fault in listOf(
            sample(3_010_000_000, Vec3(8.0, 0.0, -9.81)),
            sample(3_010_000_000, rate = Vec3(0.0, 0.5, 0.0)),
            sample(3_010_000_000).copy(clipped = true),
            sample(3_010_000_000).copy(fresh = false),
            sample(3_100_000_000),
            sample(2_990_000_000),
        )) {
            val monitor = warmed()
            monitor.observe(fault)
            assertFalse(monitor.canLearn(fault.monotonicNanos))
            (311..400).forEach { monitor.observe(sample(it * 10_000_000L)) }
            assertFalse(monitor.canLearn(4_000_000_000L))
            (401..650).forEach { monitor.observe(sample(it * 10_000_000L)) }
            assertTrue(monitor.canLearn(6_500_000_000L))
            assertFalse(monitor.canLearn(6_000_000_000L)) // Delayed GNSS cannot reopen the gate.
        }
    }

    @Test fun rollingVibrationIsDetectedEvenBelowSingleSampleShockThreshold() {
        val monitor = ImuQualityMonitor()
        (1..300).forEach { monitor.observe(sample(it * 10_000_000L)) }
        (301..450).forEach {
            monitor.observe(sample(it * 10_000_000L, Vec3(if (it % 20 < 10) 1.0 else -1.0, 0.0, -9.81)))
        }
        assertFalse(monitor.canLearn(4_500_000_000L))
    }

    private fun fix(time: Long, latitude: Double = 1.3521, velocity: Vec3 = Vec3.ZERO) = GnssFix(
        time, time / 1_000_000, latitude, 103.8198, 25.0, velocity,
        1.0, 2.0, 0.1, 5.0, 18, hasDerivedDownVelocity = true)

    private fun covariance(engine: NavigationEngine): DoubleArray =
        (NavigationEngine::class.java.getDeclaredField("covariance").apply { isAccessible = true }.get(engine) as DoubleArray).copyOf()

    @Test fun roughIntervalsHoldBiasButKeepGnssCorrectionsAndJosephCovariance() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0))
        (1..300).forEach { engine.processImu(sample(it * 10_000_000L)) }
        val before = engine.snapshot(3_000_000_000L, 0)
        (301..400).forEach { i ->
            val t = i * 10_000_000L
            engine.processImu(sample(t, Vec3(if (i % 2 == 0) 5.0 else -5.0, 0.0, -9.81), Vec3(0.0, 0.0, if (i % 2 == 0) 0.2 else -0.2)))
            if (i % 10 == 0) {
                val predicted = engine.snapshot(t, 0)
                val p0 = covariance(engine)
                engine.processGnss(fix(t, 1.35211))
                val corrected = engine.snapshot(t, 0)
                val p1 = covariance(engine)
                assertEquals(before.accelerometerBiasMps2, corrected.accelerometerBiasMps2)
                assertEquals(before.gyroBiasRadps, corrected.gyroBiasRadps)
                assertTrue(corrected.qualityFlags and QualityFlag.BIAS_LEARNING_HELD != 0)
                assertTrue(corrected.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0)
                assertTrue(corrected.latitudeDeg != predicted.latitudeDeg)
                for (bias in 9..14) assertEquals(p0[bias * 15 + bias], p1[bias * 15 + bias], 1e-12)
                for (r in 0..14) for (c in 0..14) {
                    assertTrue(p1[r * 15 + c].isFinite())
                    assertEquals(p1[r * 15 + c], p1[c * 15 + r], 1e-12)
                }
                for (seed in 1..40) {
                    val v = DoubleArray(15) { kotlin.math.sin((it + 1) * seed.toDouble()) }
                    val quadratic = (0..14).sumOf { r -> (0..14).sumOf { c -> v[r] * p1[r * 15 + c] * v[c] } }
                    assertTrue(quadratic >= -1e-10)
                }
            }
        }
        (401..750).forEach { engine.processImu(sample(it * 10_000_000L)) }
        assertEquals(0, engine.snapshot(7_500_000_000L, 0).qualityFlags and QualityFlag.BIAS_LEARNING_HELD)
    }

    @Test fun movingAccelerationIsNotMistakenForGravityTiltAndIsStillIntegrated() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0))
        (1..300).forEach { i ->
            engine.processImu(sample(i * 10_000_000L, Vec3(2.0, 0.0, -9.81)))
        }
        val result = engine.snapshot(3_000_000_000L, 0)
        assertTrue(abs(result.eulerDeg.y) < 0.1, "Dynamic specific force must not tilt the attitude: ${result.eulerDeg}")
        assertTrue(result.velocityNedMps.x > 5.0, "Real motion samples must still be integrated")
    }

    @Test fun smoothMovingGnssResidualsTeachOnlyBoundedCoupledGyroBias() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0))
        (1..800).forEach { i ->
            val t = i * 10_000_000L
            engine.processImu(sample(t, rate = Vec3(0.002, -0.001, 0.003)))
            if (i % 100 == 0) engine.processGnss(fix(t, 1.3521 + i * 0.01 * 3.0 / 111_320.0, Vec3(3.0, 0.0, 0.0)))
        }
        val estimated = engine.snapshot(8_000_000_000L, 0).gyroBiasRadps
        assertTrue(estimated.toArray().all { it.isFinite() })
        assertTrue(estimated.norm() < 0.01, "Coupled gyro-bias estimate became implausible: $estimated")
    }

    @Test fun alternatingBumpsDoNotLeavePersistentBiasBeforeOutage() {
        fun run(bumps: Boolean): NavigationSolution {
            val engine = NavigationEngine()
            engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0))
            (1..1000).forEach { i ->
                val t = i * 10_000_000L
                val sign = if (i % 2 == 0) 1.0 else -1.0
                val rough = bumps && i in 301..500
                engine.processImu(sample(t, Vec3(if (rough) 5.0 * sign else 0.0, 0.0, -9.81),
                    Vec3(0.0, 0.0, if (rough) 0.2 * sign else 0.0)))
                if (i % 100 == 0) {
                    if (i <= 500) engine.processGnss(fix(t)) else engine.observeGnssWithoutAiding(fix(t))
                }
            }
            return engine.snapshot(10_000_000_000L, 0)
        }
        val clean = run(false)
        val rough = run(true)
        assertTrue((rough.accelerometerBiasMps2 - clean.accelerometerBiasMps2).norm() < 0.01)
        assertTrue((rough.gyroBiasRadps - clean.gyroBiasRadps).norm() < 0.001)
        val displacement = (geodeticToEcef(rough.latitudeDeg, rough.longitudeDeg, rough.altitudeEllipsoidM) -
            geodeticToEcef(clean.latitudeDeg, clean.longitudeDeg, clean.altitudeEllipsoidM)).norm()
        assertTrue(displacement < 5.0, "Five-second outage rough/clean separation: $displacement m")
    }
}
