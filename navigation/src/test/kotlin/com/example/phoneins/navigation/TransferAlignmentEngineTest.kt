package com.example.phoneins.navigation

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transfer-alignment (master INS) state machine tests: a synthetic van track with a known
 * mounting yaw offset between the master body frame and the slave (phone) body frame.
 */
class TransferAlignmentEngineTest {

    private data class Tick(
        val imu: TimedImuSample,
        val reference: MasterInsReference?,
        val tNanos: Long,
    )

    /**
     * Level van driving: accelerate 0.5 m/s^2 for 20 s, a 40 degree turn over 6 s, then
     * straight at constant speed. The slave body frame is rotated by [mountYawDeg] about
     * the vertical axis relative to the master body frame.
     */
    private fun ticks(mountYawDeg: Double): List<Tick> {
        val out = ArrayList<Tick>(4550)
        val startNanos = 1_000_000_000L
        val mountRad = Math.toRadians(mountYawDeg)
        var t = 0.0
        var speed = 3.0
        var yawRad = 0.0
        var nextRef = 0.0
        while (t <= 75.0) {
            val accel = when {
                t < 20.0 -> 0.5
                t < 26.0 -> 0.0
                else -> 0.0
            }
            val yawRate = if (t in 20.0..26.0) Math.toRadians(40.0 / 6.0) else 0.0
            val accelNed = Vec3(-sin(yawRad), cos(yawRad), 0.0) * (speed * yawRate) + Vec3(cos(yawRad), sin(yawRad), 0.0) * accel
            // Master body-frame quantities (level vehicle, 3-2-1 yaw only).
            val masterBodyToNed = bodyToNedMatrix(0.0, 0.0, yawRad)
            val masterForceBody = multiply3(transpose3(masterBodyToNed), accelNed - Vec3(0.0, 0.0, 9.80665))
            val masterGyroBody = Vec3(0.0, 0.0, yawRate)
            // Slave body = master body rotated by the fixed mounting angle.
            val rotMount = bodyToNedMatrix(0.0, 0.0, mountRad)
            val slaveForceBody = multiply3(transpose3(rotMount), masterForceBody)
            val slaveGyroBody = multiply3(transpose3(rotMount), masterGyroBody)
            val tNanos = startNanos + (t * 1e9).toLong()
            if (t >= nextRef) {
                nextRef += 0.05
                val velocityNed = Vec3(cos(yawRad) * speed, sin(yawRad) * speed, 0.0)
                out += Tick(
                    TimedImuSample(tNanos, slaveForceBody, slaveGyroBody),
                    MasterInsReference(
                        sequence = (nextRef * 20).toLong(),
                        masterMonotonicNanos = tNanos,
                        masterUtcNanos = 0L,
                        phoneMonotonicNanos = tNanos,
                        flags = MasterReferenceFlag.VELOCITY_VALID or MasterReferenceFlag.ATTITUDE_VALID or
                            MasterReferenceFlag.ANGULAR_RATE_VALID or MasterReferenceFlag.UNCERTAINTY_VALID,
                        latitudeDeg = 1.0,
                        longitudeDeg = 2.0,
                        altitudeM = 10.0,
                        velocityNedMps = velocityNed,
                        bodyToNed = Quaternion.fromMatrix(masterBodyToNed).normalized(),
                        angularRateBodyRadps = masterGyroBody,
                        velocityStdNedMps = Vec3(0.1, 0.1, 0.2),
                        attitudeStdDeg = Vec3(1.0, 1.0, 1.0),
                        timeUncertaintyNanos = 0L,
                    ),
                    tNanos,
                )
            } else {
                out += Tick(TimedImuSample(tNanos, slaveForceBody, slaveGyroBody), null, tNanos)
            }
            t += 0.01
            speed = (speed + accel * 0.01).coerceAtLeast(0.0)
            yawRad += yawRate * 0.01
        }
        // Attach the aligned IMU timestamps to the final reference too.
        return out
    }

    private fun alignedEngine(mountYawDeg: Double): NavigationEngine {
        val engine = NavigationEngine()
        engine.beginTransferAlignment()
        for (tick in ticks(mountYawDeg)) {
            engine.processImu(tick.imu)
            tick.reference?.let(engine::processMasterReference)
        }
        return engine
    }

    @Test
    fun transferAlignmentPromotesAndEstimatesMountingAngle() {
        val engine = alignedEngine(mountYawDeg = 20.0)
        val solution = engine.snapshot(76_000_000_000L, 0)
        assertEquals(AlignmentPhase.READY, solution.alignmentPhase)
        assertTrue(abs(solution.mountingEulerDeg.z - 20.0) < 5.0, "mounting=${solution.mountingEulerDeg}")
        assertTrue(abs(solution.mountingEulerDeg.x) < 5.0, "mounting=${solution.mountingEulerDeg}")
        assertTrue(abs(solution.mountingEulerDeg.y) < 5.0, "mounting=${solution.mountingEulerDeg}")
    }

    @Test
    fun gnssIsIgnoredDuringAlignmentAndAcceptedWhenReady() {
        val engine = NavigationEngine()
        engine.beginTransferAlignment()
        val fix = GnssFix(
            2_000_000_000L, 1_700_000_000_000L, 1.00001, 2.00001, 10.5,
            Vec3(9.0, 1.0, 0.0), 2.0, 4.0, 0.2, 5.0, 16,
        )
        var injectedDuringAlignment = false
        for (tick in ticks(mountYawDeg = 20.0)) {
            engine.processImu(tick.imu)
            tick.reference?.let(engine::processMasterReference)
            if (!injectedDuringAlignment && tick.tNanos >= 2_000_000_000L) {
                injectedDuringAlignment = true
                engine.processGnss(fix.copy(monotonicNanos = tick.tNanos))
                val during = engine.snapshot(tick.tNanos, 0)
                assertEquals(AlignmentPhase.ALIGNING, during.alignmentPhase)
                // GNSS is withheld while the alignment is running: the fix was not accepted.
                assertEquals(FixStatus.NO_FIX, during.fixStatus)
            }
        }
        engine.processGnss(fix.copy(monotonicNanos = 77_000_000_000L))
        val after = engine.snapshot(77_100_000_000L, 0)
        assertEquals(AlignmentPhase.READY, after.alignmentPhase)
        assertTrue(after.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0, "flags=${after.qualityFlags}")
    }

    @Test
    fun alignmentDoesNotPromoteBeforeConvergence() {
        val engine = NavigationEngine()
        engine.beginTransferAlignment()
        var promoted: AlignmentPhase? = null
        for (tick in ticks(mountYawDeg = 20.0)) {
            engine.processImu(tick.imu)
            tick.reference?.let(engine::processMasterReference)
            val phase = engine.snapshot(tick.tNanos, 0).alignmentPhase
            if (tick.tNanos <= 10_000_000_000L + 1_000_000_000L && phase == AlignmentPhase.READY) {
                promoted = phase
            }
        }
        assertNull(promoted, "Promoted within the first 10 s, before covariance convergence")
        assertEquals(AlignmentPhase.READY, engine.snapshot(76_000_000_000L, 0).alignmentPhase)
    }
}
