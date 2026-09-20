package com.example.phoneins.service

import com.example.phoneins.navigation.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DeterministicSimulatorTest {
    @Test fun remainsStationaryUntilExplicitStart() {
        val simulator = DeterministicSimulator(0, 0)
        for (seconds in listOf(0, 4, 15, 30, 300)) {
            val tick = simulator.tick(seconds * 1_000_000_000L)
            assertEquals(Vec3(0.0, 0.0, -9.80665), tick.imu.accelerationMps2)
            assertEquals(Vec3.ZERO, tick.gnss!!.velocityNedMps)
            assertEquals(1.3521, tick.gnss!!.latitudeDeg, 0.0)
        }
        simulator.startMotion(301_000_000_000L)
        assertEquals(4.0, simulator.tick(303_000_000_000L).gnss!!.velocityNedMps.x, 1e-9)
        simulator.startMotion(303_000_000_000L) // Repeated command must not restart the route.
        assertEquals(8.0, simulator.tick(305_000_000_000L).gnss!!.velocityNedMps.x, 1e-9)
    }

    private fun fix(t: Double): GnssFix = DeterministicSimulator(0, 0).also { it.startMotion(0) }
        .tick((t * 1e9).toLong()).gnss!!

    @Test fun positionDerivativeMatchesVelocityThroughTurn() {
        for (t in listOf(1.0, 3.0, 4.0, 7.0, 8.0, 11.0, 15.0, 16.0, 25.0)) {
            val a = fix(t - 0.001)
            val b = fix(t + 0.001)
            val lat = Math.toRadians(1.3521)
            val factor = 1.0 - 6.69437999014e-3 * sin(lat).pow(2)
            val m = 6378137.0 * (1.0 - 6.69437999014e-3) / factor.pow(1.5)
            val n = 6378137.0 / sqrt(factor)
            val ned = Vec3(Math.toRadians(b.latitudeDeg - a.latitudeDeg) * (m + 25.0) / 0.002,
                Math.toRadians(b.longitudeDeg - a.longitudeDeg) * (n + 25.0) * cos(lat) / 0.002,
                -(b.altitudeEllipsoidM - a.altitudeEllipsoidM) / 0.002)
            assertTrue("t=$t: $ned", (ned - fix(t).velocityNedMps).norm() < 0.005)
        }
    }

    @Test fun manualInitializationAtRestDoesNotCreateHorizontalDrift() {
        for (aided in listOf(true, false)) {
            val simulator = DeterministicSimulator(0, 0)
            val samples = (1..320).map { simulator.tick(it * 10_000_000L).imu }
            val (accel, rate) = staticImuMeans(samples, 3_200_000_000L)
            val engine = NavigationEngine()
            engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0), accel, rate)
            for (i in 321..3320) {
                val tick = simulator.tick(i * 10_000_000L)
                engine.processImu(tick.imu)
                tick.gnss?.let { if (aided) engine.processGnss(it) else engine.observeGnssWithoutAiding(it) }
            }
            val solution = engine.snapshot(33_200_000_000L, 0)
            assertTrue(solution.velocityNedMps.norm() < 0.01)
            assertEquals(1.3521, solution.latitudeDeg, 1e-6)
            assertEquals(103.8198, solution.longitudeDeg, 1e-6)
        }
    }

    @Test fun manualBiasEstimationRejectsInsufficientStaleAndMovingSamples() {
        val samples = (1..320).map { TimedImuSample(it * 10_000_000L, Vec3(0.0, 0.0, -9.80665), Vec3.ZERO) }
        staticImuMeans(samples, 3_200_000_000L)
        fun rejected(input: List<TimedImuSample>, now: Long = 3_200_000_000L) {
            assertThrows(IllegalArgumentException::class.java) { staticImuMeans(input, now) }
        }
        rejected(emptyList())
        rejected(samples.takeLast(10))
        rejected(samples, 4_000_000_000L)
        rejected(samples.map { it.copy(accelerationMps2 = Vec3(2.0, 0.0, -9.80665)) })
        rejected(samples.map { it.copy(angularRateRadps = Vec3(0.0, 0.0, 0.14)) })
        rejected(samples.map { it.copy(fresh = false) })
    }
}
