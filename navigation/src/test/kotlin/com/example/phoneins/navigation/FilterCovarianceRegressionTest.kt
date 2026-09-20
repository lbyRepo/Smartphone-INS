package com.example.phoneins.navigation

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the coupled-filter repair: frame-explicit covariance resets, covariance
 * validity through updates/injections/resets, outage isolation, retained uncertainty under
 * unobservable conditions, near-vertical mounting and the finite-difference attitude/force
 * coupling in mechanization.
 */
class FilterCovarianceRegressionTest {

    private val stateSize = 15
    private val position = 0
    private val velocity = 3
    private val attitude = 6
    private val accelBias = 9

    private fun covariance(engine: NavigationEngine): DoubleArray =
        (field(engine, "covariance") as DoubleArray).copyOf()

    private fun field(engine: NavigationEngine, name: String): Any? =
        NavigationEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)

    private fun diagonalBlockStd(matrix: DoubleArray, offset: Int): Vec3 = Vec3(
        sqrt(matrix[(offset + 0) * stateSize + offset + 0].coerceAtLeast(0.0)),
        sqrt(matrix[(offset + 1) * stateSize + offset + 1].coerceAtLeast(0.0)),
        sqrt(matrix[(offset + 2) * stateSize + offset + 2].coerceAtLeast(0.0)),
    )

    /** Rotates the ECEF covariance block into NED and returns the resulting diagonal stds. */
    private fun projectBlockToNed(matrix: DoubleArray, offset: Int, latitudeDeg: Double, longitudeDeg: Double): Vec3 {
        val ecefToNed = ecefToNedMatrix(Math.toRadians(latitudeDeg), Math.toRadians(longitudeDeg))
        val ned = Array(3) { DoubleArray(3) }
        for (row in 0..2) for (col in 0..2) {
            var value = 0.0
            for (i in 0..2) for (j in 0..2) {
                value += ecefToNed[row * 3 + i] * matrix[(offset + i) * stateSize + offset + j] * ecefToNed[col * 3 + j]
            }
            ned[row][col] = value
        }
        return Vec3(
            sqrt(ned[0][0].coerceAtLeast(0.0)),
            sqrt(ned[1][1].coerceAtLeast(0.0)),
            sqrt(ned[2][2].coerceAtLeast(0.0)),
        )
    }

    private fun assertValidCovariance(matrix: DoubleArray, label: String) {
        for (row in 0 until stateSize) for (col in 0 until stateSize) {
            val value = matrix[row * stateSize + col]
            assertTrue(value.isFinite(), "$label: non-finite covariance at $row,$col")
            assertEquals(value, matrix[col * stateSize + row], 1e-12, "$label: covariance not symmetric at $row,$col")
        }
        for (seed in 1..24) {
            val v = DoubleArray(stateSize) { kotlin.math.sin((it + 1) * seed * 0.7) }
            var quadratic = 0.0
            for (row in 0 until stateSize) for (col in 0 until stateSize) {
                quadratic += v[row] * matrix[row * stateSize + col] * v[col]
            }
            assertTrue(quadratic >= -1e-9, "$label: negative quadratic form $quadratic for seed $seed")
        }
    }

    private fun fix(
        t: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
        velocityNed: Vec3 = Vec3.ZERO,
        horizontalAccuracyM: Double = 2.0,
        verticalAccuracyM: Double = 4.0,
        speedAccuracyMps: Double = 0.2,
    ) = GnssFix(
        monotonicNanos = t,
        unixMillis = t / 1_000_000,
        latitudeDeg = latitudeDeg,
        longitudeDeg = longitudeDeg,
        altitudeEllipsoidM = 25.0,
        velocityNedMps = velocityNed,
        horizontalAccuracyM = horizontalAccuracyM,
        verticalAccuracyM = verticalAccuracyM,
        speedAccuracyMps = speedAccuracyMps,
        bearingAccuracyDeg = 5.0,
        satellitesUsed = 16,
        hasAltitude = true,
        hasHorizontalVelocity = true,
        hasDerivedDownVelocity = true,
    )

    private fun stepImu(engine: NavigationEngine, index: Int, force: Vec3, gyro: Vec3 = Vec3.ZERO) =
        engine.processImu(TimedImuSample(index * 10_000_000L, force, gyro))

    @Test
    fun reacquisitionResetsUncertaintyInTheNedFrame() {
        // Two latitudes: near-equator (longitude is the discriminating axis) and mid-latitude
        // (ECEF and NED axes are strongly rotated), so an ECEF/NED frame mix-up cannot pass.
        for (latitudeDeg in listOf(1.3521, 51.5)) {
            val engine = NavigationEngine()
            engine.initializeManual(ManualInitialization(latitudeDeg, 103.8198, 25.0, 0.0, 0.0, 0.0))
            engine.setGnssAidingEnabled(false)
            repeat(300) { stepImu(engine, it + 1, Vec3(0.2, -0.1, -9.80665), Vec3(0.001, 0.002, -0.001)) }
            // Aiding stays enabled while the outage fix is recorded for display only, so the next
            // fix is a reacquisition: it reanchors instead of applying a Kalman correction.
            engine.setGnssAidingEnabled(true)
            engine.observeGnssWithoutAiding(fix(3_900_000_000L, latitudeDeg, 103.8198, Vec3(8.0, 1.0, 0.0)))
            // Deliberately unequal horizontal/vertical and horizontal/down accuracies.
            engine.processGnss(
                fix(
                    4_000_000_000L, latitudeDeg + 3e-5, 103.8198, Vec3(8.0, 1.0, 0.0),
                    horizontalAccuracyM = 2.0, verticalAccuracyM = 30.0, speedAccuracyMps = 0.5,
                ),
            )
            val matrix = covariance(engine)
            assertValidCovariance(matrix, "post-reacquisition at $latitudeDeg")
            val positionStd = projectBlockToNed(matrix, position, latitudeDeg, 103.8198)
            val velocityStd = projectBlockToNed(matrix, velocity, latitudeDeg, 103.8198)
            assertEquals(positionStd.x, positionStd.y, positionStd.x * 0.05 + 1e-6, "Position reset is not horizontal-isotropic: $positionStd")
            assertTrue(positionStd.z > positionStd.x * 5.0, "Vertical position uncertainty was not taken from the fix: $positionStd")
            assertEquals(velocityStd.x, velocityStd.y, velocityStd.x * 0.05 + 1e-6, "Velocity reset is not horizontal-isotropic: $velocityStd")
            assertTrue(velocityStd.z > velocityStd.x * 2.0, "Down velocity uncertainty was not taken from the fix: $velocityStd")
            // The reanchored covariance must equal the fix's own NED accuracies: this fails if the
            // NED block is written from the wrong latitude/longitude or in the wrong frame.
            assertEquals(2.0, positionStd.x, 0.02, "Horizontal position uncertainty does not match the fix: $positionStd")
            assertEquals(30.0, positionStd.z, 0.2, "Vertical position uncertainty does not match the fix: $positionStd")
            assertEquals(0.5, velocityStd.x, 0.01, "Horizontal velocity uncertainty does not match the fix: $velocityStd")
            assertEquals(1.5, velocityStd.z, 0.05, "Down velocity uncertainty does not match the fix: $velocityStd")
        }
    }

    @Test
    fun covarianceStaysValidThroughUpdatesInjectionsAndReset() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0))
        assertValidCovariance(covariance(engine), "post-initialization")
        repeat(600) { index ->
            stepImu(engine, index + 1, Vec3(0.5, -0.2, -9.80665), Vec3(0.003, -0.002, 0.004))
            if (index % 100 == 0) {
                engine.processGnss(
                    fix(
                        (index + 1) * 10_000_000L,
                        1.3521 + index * 2e-6,
                        103.8198 + index * 2e-6,
                        Vec3(6.0, 1.0, 0.0),
                    ),
                )
                assertValidCovariance(covariance(engine), "after fix $index")
            }
        }
        engine.reset()
        assertValidCovariance(covariance(engine), "post-reset")
    }

    @Test
    fun withheldAidingAppliesNoCorrectionsEvenWithZeroSpeedFixes() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0))
        engine.setGnssAidingEnabled(false)
        val before = engine.snapshot(0L, 0)
        // 45 s of sustained horizontal specific force with a raw zero-speed GNSS fix every second.
        repeat(4500) { index ->
            stepImu(engine, index + 1, Vec3(1.6, 0.4, -9.80665), Vec3(0.001, -0.001, 0.002))
            if (index % 100 == 0) engine.processGnss(fix((index + 1) * 10_000_000L, 1.3521, 103.8198))
        }
        val after = engine.snapshot(45_000_000_000L, 0)
        assertEquals(before.accelerometerBiasMps2, after.accelerometerBiasMps2, "Accelerometer bias changed during outage isolation")
        assertEquals(before.gyroBiasRadps, after.gyroBiasRadps, "Gyro bias changed during outage isolation")
        assertEquals(0, after.qualityFlags and QualityFlag.GNSS_POSITION_VALID, "Raw GNSS must not report position aiding during an outage")
        assertTrue(after.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0, "Aiding suspension must be reported")
        assertTrue(after.qualityFlags and QualityFlag.DEAD_RECKONING != 0, "Outage must report dead reckoning")
        val deadReckonedSpeed = hypot(after.velocityNedMps.x, after.velocityNedMps.y)
        assertTrue(deadReckonedSpeed > 20.0, "Dead reckoning did not integrate the sustained force: $deadReckonedSpeed m/s")
        // Restoring aiding must reanchor to the directly observed state, not trust the outage residual.
        engine.setGnssAidingEnabled(true)
        engine.processGnss(fix(45_100_000_000L, 1.3521, 103.8198, Vec3.ZERO))
        val reacquired = engine.snapshot(45_100_000_000L, 0)
        val reacquiredSpeed = hypot(reacquired.velocityNedMps.x, reacquired.velocityNedMps.y)
        assertTrue(reacquiredSpeed < 2.0, "Reacquisition did not restore the observed velocity: $reacquiredSpeed m/s")
        assertTrue(reacquired.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0, "Aiding did not resume")
        // Consistent fixes must recover the dead-reckoned outage displacement within a few fixes
        // (Kalman update when it is accepted, consistent-rejection reanchor when it is not).
        var index = 4521
        for (step in 1..4) {
            repeat(100) { stepImu(engine, index++, Vec3(0.0, 0.0, -9.80665)) }
            engine.processGnss(fix(index * 10_000_000L, 1.3521, 103.8198, Vec3.ZERO))
        }
        val recovered = engine.snapshot(index * 10_000_000L, 0)
        val recoveryError = hypot(
            (recovered.latitudeDeg - 1.3521) * 111_132.0,
            (recovered.longitudeDeg - 103.8198) * 111_320.0,
        )
        assertTrue(recoveryError < 25.0, "Reacquisition did not recover the outage displacement: $recoveryError m")
    }

    @Test
    fun unobservableStateUncertaintyIsNotFalselyReduced() {
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0))
        engine.setGnssAidingEnabled(false)
        val before = diagonalBlockStd(covariance(engine), attitude)
        val biasBefore = diagonalBlockStd(covariance(engine), accelBias)
        // Pure stationary dead reckoning: nothing observes attitude or bias, so their uncertainty
        // must not collapse as if a measurement had been taken.
        repeat(300) { stepImu(engine, it + 1, Vec3(0.0, 0.0, -9.80665)) }
        val matrix = covariance(engine)
        assertValidCovariance(matrix, "stationary unobservable")
        val after = diagonalBlockStd(matrix, attitude)
        val biasAfter = diagonalBlockStd(matrix, accelBias)
        for (axis in listOf("x", "y", "z")) {
            fun Vec3.axis() = when (axis) { "x" -> x; "y" -> y; else -> z }
            assertTrue(after.axis() >= before.axis() * 0.999,
                "Attitude uncertainty shrank without an observation on $axis: ${before.axis()} -> ${after.axis()}")
            assertTrue(after.axis() > 0.05, "Attitude uncertainty collapsed on $axis: ${after.axis()}")
            assertTrue(biasAfter.axis() >= biasBefore.axis() * 0.999,
                "Bias uncertainty shrank without an observation on $axis: ${biasBefore.axis()} -> ${biasAfter.axis()}")
        }
    }

    @Test
    fun nearVerticalMountingStaysBoundedUnderAiding() {
        val rollDeg = 81.0
        val pitchDeg = -40.0
        val yawDeg = 10.0
        val engine = NavigationEngine()
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, rollDeg, pitchDeg, yawDeg))
        engine.setGnssAidingEnabled(true)
        // Near-vertical body X axis: gravity projects almost entirely onto the first body axis.
        val restForce = multiply3(
            transpose3(bodyToNedMatrix(Math.toRadians(rollDeg), Math.toRadians(pitchDeg), Math.toRadians(yawDeg))),
            Vec3(0.0, 0.0, -9.80665),
        )
        var latitude = 1.3521
        repeat(600) { index ->
            stepImu(engine, index + 1, restForce, Vec3(0.0005, -0.0005, 0.001))
            if (index % 100 == 0) {
                latitude += 3.0 * 0.1 / 111_132.0
                engine.processGnss(fix((index + 1) * 10_000_000L, latitude, 103.8198, Vec3(3.0, 0.0, 0.0)))
            }
        }
        val solution = engine.snapshot(6_050_000_000L, 0)
        assertTrue(
            solution.latitudeDeg.isFinite() && solution.longitudeDeg.isFinite() &&
                solution.eulerDeg.toArray().all { it.isFinite() } &&
                solution.velocityNedMps.toArray().all { it.isFinite() },
            "Near-vertical mounting produced a non-finite state",
        )
        val error = hypot((solution.latitudeDeg - latitude) * 111_132.0, (solution.longitudeDeg - 103.8198) * 111_320.0)
        assertTrue(error < 25.0, "Near-vertical mounting drifted $error m")
        assertTrue(solution.attitudeStdDeg.toArray().all { it.isFinite() && it > 0.0 }, "Attitude uncertainty is invalid: ${solution.attitudeStdDeg}")
    }

    @Test
    fun finiteDifferenceAttitudeForceCouplingMatchesPropagation() {
        val force = Vec3(1.0, 0.0, -9.80665)
        val dt = 0.01
        val epsilon = 1e-6
        // Two steps: the first only primes the engine (no propagation without a previous sample),
        // the second integrates one interval after the attitude has been perturbed.
        fun velocityAfterPerturbation(axis: Int, angle: Double): Vec3 {
            val probe = NavigationEngine(imuSmoothingWindow = 1)
            probe.initializeManual(
                ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0),
                stationaryAcceleration = force,
            )
            probe.setGnssAidingEnabled(false)
            stepImu(probe, 1, force)
            val attitudeField = NavigationEngine::class.java.getDeclaredField("bodyToEcef").apply { isAccessible = true }
            if (angle != 0.0) {
                val current = attitudeField.get(probe) as Quaternion
                val delta = DoubleArray(3).also { it[axis] = angle }
                attitudeField.set(probe, (Quaternion.fromRotationVector(Vec3.from(delta)) * current).normalized())
            }
            stepImu(probe, 2, force)
            return (field(probe, "velocityEcef") as Vec3) / dt
        }

        val baseline = velocityAfterPerturbation(0, 0.0)
        val reference = NavigationEngine(imuSmoothingWindow = 1)
        reference.initializeManual(
            ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0),
            stationaryAcceleration = force,
        )
        reference.setGnssAidingEnabled(false)
        stepImu(reference, 1, force)
        val attitudeMatrix = (field(reference, "bodyToEcef") as Quaternion).toMatrix()
        val effectiveForce = force - reference.snapshot(10_000_000L, 0).accelerometerBiasMps2
        val specificForceEcef = multiply3(attitudeMatrix, effectiveForce)
        // Mechanization coupling for a LEFT ECEF attitude error: dv/dtheta = -[C f]x.
        val analytic = Array(3) { row -> DoubleArray(3) { col -> -skew3(specificForceEcef)[row * 3 + col] } }

        for (axis in 0..2) {
            val plus = velocityAfterPerturbation(axis, epsilon)
            val minus = velocityAfterPerturbation(axis, -epsilon)
            val numeric = (plus - minus) / (2 * epsilon)
            for (row in 0..2) {
                assertEquals(
                    analytic[row][axis],
                    numeric.toArray()[row],
                    1e-6,
                    "Attitude/force coupling mismatch at row $row, axis $axis (baseline ${baseline.toArray()[row]})",
                )
            }
        }
    }

    @Test
    fun velocityObservationGainScaleZeroHoldsBiasExactly() {
        val engine = NavigationEngine(imuSmoothingWindow = 1)
        engine.initializeManual(ManualInitialization(1.3521, 103.8198, 25.0, 0.0, 0.0, 0.0))
        // Establish an estimate well away from the initialized bias so a leak is visible.
        engine.setGnssAidingEnabled(false)
        repeat(1200) { stepImu(engine, it + 1, Vec3(0.9, -0.4, -9.80665), Vec3(0.004, -0.003, 0.002)) }
        val method = NavigationEngine::class.java.getDeclaredMethod(
            "updateNedMeasurement",
            Vec3::class.java, Int::class.javaPrimitiveType, DoubleArray::class.java, DoubleArray::class.java,
            Double::class.javaPrimitiveType, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Double::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val before = field(engine, "accelerometerBias") as Vec3
        val gyroBefore = field(engine, "gyroBias") as Vec3
        val ecefToNed = ecefToNedMatrix(Math.toRadians(1.3521), 0.0)
        repeat(5) {
            method.invoke(
                engine, Vec3(3.0, -1.0, 0.5), velocity, ecefToNed,
                doubleArrayOf(0.5, 0.5, 1.5), Double.NaN, 7, 0.0, false, 1.0,
            )
        }
        assertEquals(before, field(engine, "accelerometerBias") as Vec3, "Held accelerometer bias changed")
        assertEquals(gyroBefore, field(engine, "gyroBias") as Vec3, "Held gyro bias changed")
        // The velocity state itself must still be corrected: position/velocity stay coupled.
        val corrected = (field(engine, "velocityEcef") as Vec3)
        assertTrue(corrected.norm() > 0.0, "Velocity state was not corrected")
    }
}

private fun skew3(v: Vec3) = doubleArrayOf(
    0.0, -v.z, v.y,
    v.z, 0.0, -v.x,
    -v.y, v.x, 0.0,
)
