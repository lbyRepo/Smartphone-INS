package com.example.phoneins.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationRobustnessTest {
    @Test fun heldImuDoesNotPropagateOrClaimFreshness() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3, 103.8, 20.0, 0.0, 0.0, 0.0))
        engine.processImu(TimedImuSample(1_000_000_000L, Vec3(0.0, 0.0, -9.81), Vec3.ZERO))
        val before = engine.snapshot(1_000_000_000L, 0L)
        engine.processImu(TimedImuSample(1_010_000_000L, Vec3(100.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0), fresh = false))
        val after = engine.snapshot(1_010_000_000L, 0L)
        assertEquals(before.velocityNedMps, after.velocityNedMps)
        assertEquals(before.eulerDeg, after.eulerDeg)
        assertEquals(0, after.qualityFlags and QualityFlag.IMU_FRESH)
        assertTrue(after.qualityFlags and QualityFlag.IMU_HELD != 0)
    }

    @Test fun uncertaintyIsRotatedRatherThanRelabelled() {
        val covariance = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 9.0)
        val local = projectedStandardDeviation(covariance, 3, 0, ecefToNedMatrix(0.0, 0.0))
        assertEquals(Vec3(3.0, 2.0, 1.0), local)
    }

    @Test fun eulerUncertaintyJacobianMatchesFiniteDifferences() {
        val pitch = 0.4
        val yaw = 1.2
        val rotation = bodyToNedMatrix(-0.3, pitch, yaw)
        val jacobian = nedRotationToEulerJacobian(pitch, yaw)
        val epsilon = 1e-6
        for (axis in 0..2) {
            val delta = Vec3.from(DoubleArray(3).also { it[axis] = epsilon })
            val plus = eulerFromBodyToNed(multiply3(Quaternion.fromRotationVector(delta).toMatrix(), rotation))
            val minus = eulerFromBodyToNed(multiply3(Quaternion.fromRotationVector(delta * -1.0).toMatrix(), rotation))
            val derivative = ((plus - minus) * (Math.PI / 180.0 / (2 * epsilon))).toArray()
            for (row in 0..2) assertEquals(jacobian[row * 3 + axis], derivative[row], 1e-7)
        }
    }

    private fun fix(down: Double, available: Boolean = false) = GnssFix(
        1_000_000_000L, 0L, 1.3, 103.8, 20.0,
        Vec3(3.0, 1.0, down), 3.0, 5.0, 0.2, 5.0, 10,
        hasDerivedDownVelocity = available,
    )

    @Test fun absentOrInvalidDownVelocityCannotPoisonHorizontalUpdate() {
        var baseline: Vec3? = null
        for (down in listOf(Double.NaN, Double.POSITIVE_INFINITY, 99.0)) {
            for (available in listOf(false, true)) {
                if (available && down.isFinite()) continue
                val engine = NavigationEngine()
                engine.initializeManual(ManualInitialization(1.3, 103.8, 20.0, 0.0, 0.0, 0.0))
                engine.processGnss(fix(down, available))
                val result = engine.snapshot(1_000_000_000L, 0L)
                assertTrue(result.velocityNedMps.toArray().all { it.isFinite() })
                assertTrue(result.latitudeDeg.isFinite() && result.eulerDeg.toArray().all { it.isFinite() })
                assertTrue(result.velocityNedMps.x > 2.0)
                // Horizontal updates may legitimately affect Down through cross covariance,
                // but the absent/invalid Down value must never influence the result.
                baseline?.let { assertEquals(it.z, result.velocityNedMps.z, 1e-9) }
                baseline = result.velocityNedMps
                assertEquals(0, result.qualityFlags and QualityFlag.GNSS_DOWN_VELOCITY_VALID)
            }
        }
    }

    @Test fun automaticInitializationUsesFinitePriorsForUnavailableVelocity() {
        val engine = NavigationEngine()
        engine.initializeAutomatic(fix(Double.NaN), Vec3(0.0, 0.0, -9.81), Vec3.ZERO, Vec3(30.0, 0.0, 35.0), 0.0)
        val result = engine.snapshot(1_000_000_000L, 0L)
        assertEquals(3.0, result.velocityNedMps.x, 1e-9)
        assertEquals(0.0, result.velocityNedMps.z, 1e-9)
    }

    @Test fun missingDownReacquisitionPreservesFiniteState() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3, 103.8, 20.0, 0.0, 0.0, 0.0))
        engine.observeGnssWithoutAiding(fix(Double.NaN))
        engine.processGnss(fix(Double.NaN).copy(monotonicNanos = 2_000_000_000L, velocityNedMps = Vec3(30.0, 0.0, Double.NaN)))
        assertTrue(engine.snapshot(2_000_000_000L, 0L).velocityNedMps.toArray().all { it.isFinite() })
    }

    @Test fun ecefAttitudeJacobianMatchesLeftRotationFiniteDifferences() {
        val force = Vec3(1.2, -0.7, -9.3)
        for (angles in listOf(Vec3.ZERO, Vec3(0.3, -0.5, 1.2), Vec3(-0.7, 0.6, -2.0))) {
            val rotation = bodyToNedMatrix(angles.x, angles.y, angles.z)
            val skew = ecefSpecificForceSkew(rotation, force)
            val epsilon = 1e-6
            for (axis in 0..2) {
                val delta = DoubleArray(3).also { it[axis] = epsilon }
                val plus = multiply3(multiply3(Quaternion.fromRotationVector(Vec3.from(delta)).toMatrix(), rotation), force)
                val minus = multiply3(multiply3(Quaternion.fromRotationVector(Vec3.from(delta) * -1.0).toMatrix(), rotation), force)
                val derivative = ((plus - minus) / (2 * epsilon)).toArray()
                for (row in 0..2) assertEquals(-skew[row * 3 + axis], derivative[row], 1e-7)
            }
        }
    }
}
