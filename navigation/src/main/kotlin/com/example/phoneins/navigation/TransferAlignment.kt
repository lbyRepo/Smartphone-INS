package com.example.phoneins.navigation

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

enum class TransferAlignmentPhase {
    DISABLED, WAITING_FOR_MASTER, STRAIGHT_1, MANEUVER, STRAIGHT_2, CONVERGED,
}

data class TransferAlignmentResult(
    val attitudeCorrectionNedRad: Vec3,
    val accelerometerBiasCorrectionMps2: Vec3,
    val gyroBiasCorrectionRadps: Vec3,
    val mountingEulerDeg: Vec3,
    val attitudeStdDeg: Vec3,
    val accelerometerBiasStdMps2: Vec3,
    val gyroBiasStdRadps: Vec3,
)

data class TransferAlignmentStatus(
    val phase: TransferAlignmentPhase = TransferAlignmentPhase.DISABLED,
    val mountingEulerDeg: Vec3 = Vec3.ZERO,
    val message: String = "Transfer alignment disabled",
)

/**
 * Reiner/Ben-Jaacov-inspired velocity-match shadow filter. The state is
 * [velocity error NED, attitude misalignment NED, gyro-bias error body,
 * accelerometer-bias error body]. Measurement updates are deliberately frozen
 * during the manoeuvre and resume on the second straight segment.
 */
class TransferAlignmentEstimator(
    leverArmFrdM: Vec3 = Vec3.ZERO,
) {
    private var leverArm = leverArmFrdM
    private var phase = TransferAlignmentPhase.WAITING_FOR_MASTER
    private var state = DoubleArray(STATE_SIZE)
    private var covariance = DoubleArray(STATE_SIZE * STATE_SIZE)
    private var previousReference: MasterInsReference? = null
    private var straightStartNanos = Long.MIN_VALUE
    private var firstStraightHeadingRad = Double.NaN
    private var acceptedUpdates = 0
    private var totalUpdates = 0
    private var squaredResidualSum = 0.0
    private var residualSamples = 0
    private var latestResidual = Vec3.ZERO
    private val angleHistory = ArrayDeque<Pair<Long, Vec3>>()

    init { resetCovariance() }

    fun reset(newLeverArmFrdM: Vec3 = leverArm) {
        leverArm = newLeverArmFrdM
        phase = TransferAlignmentPhase.WAITING_FOR_MASTER
        state.fill(0.0)
        resetCovariance()
        previousReference = null
        straightStartNanos = Long.MIN_VALUE
        firstStraightHeadingRad = Double.NaN
        acceptedUpdates = 0
        totalUpdates = 0
        squaredResidualSum = 0.0
        residualSamples = 0
        latestResidual = Vec3.ZERO
        angleHistory.clear()
    }

    fun update(reference: MasterInsReference, phone: NavigationSolution): TransferAlignmentStatus {
        require(phone.initialized) { "Phone INS must be initialized before transfer alignment" }
        require(reference.requiredFieldsValid) { "Master reference is missing required fields" }
        val previous = previousReference
        previousReference = reference
        val masterEuler = eulerFromBodyToNed(reference.bodyToNed.toMatrix())
        val headingRad = Math.toRadians(masterEuler.z)
        val speed = hypot(reference.velocityNedMps.x, reference.velocityNedMps.y)
        val dt = previous?.let { (reference.phoneMonotonicNanos - it.phoneMonotonicNanos) * 1e-9 } ?: 0.0
        if (dt in 0.001..0.2) propagate(phone, dt)

        val horizontalAcceleration = if (previous != null && dt in 0.001..0.2) {
            hypot(
                (reference.velocityNedMps.x - previous.velocityNedMps.x) / dt,
                (reference.velocityNedMps.y - previous.velocityNedMps.y) / dt,
            )
        } else Double.POSITIVE_INFINITY
        val yawRate = abs(reference.angularRateBodyRadps.z)
        val stableStraight = speed >= MIN_SPEED_MPS && horizontalAcceleration <= MAX_STRAIGHT_ACCEL_MPS2 &&
            yawRate <= MAX_STRAIGHT_YAW_RATE_RADPS

        val masterAtPhone = reference.velocityNedMps + multiply3(
            reference.bodyToNed.toMatrix(),
            reference.angularRateBodyRadps.cross(leverArm),
        )
        latestResidual = phone.velocityNedMps - masterAtPhone

        when (phase) {
            TransferAlignmentPhase.WAITING_FOR_MASTER -> {
                phase = TransferAlignmentPhase.STRAIGHT_1
                straightStartNanos = if (stableStraight) reference.phoneMonotonicNanos else Long.MIN_VALUE
            }
            TransferAlignmentPhase.STRAIGHT_1 -> {
                updateStraightTimer(stableStraight, reference.phoneMonotonicNanos)
                if (stableStraight) updateVelocity(latestResidual, reference.velocityStdNedMps)
                if (straightDuration(reference.phoneMonotonicNanos) >= REQUIRED_STRAIGHT_NANOS) {
                    firstStraightHeadingRad = headingRad
                    phase = TransferAlignmentPhase.MANEUVER
                    straightStartNanos = Long.MIN_VALUE
                }
            }
            TransferAlignmentPhase.MANEUVER -> {
                if (abs(wrapRadians(headingRad - firstStraightHeadingRad)) >= REQUIRED_HEADING_CHANGE_RAD && stableStraight) {
                    phase = TransferAlignmentPhase.STRAIGHT_2
                    straightStartNanos = reference.phoneMonotonicNanos
                }
            }
            TransferAlignmentPhase.STRAIGHT_2 -> {
                updateStraightTimer(stableStraight, reference.phoneMonotonicNanos)
                if (stableStraight) updateVelocity(latestResidual, reference.velocityStdNedMps)
                recordAngle(reference.phoneMonotonicNanos)
                if (straightDuration(reference.phoneMonotonicNanos) >= REQUIRED_STRAIGHT_NANOS && converged()) {
                    phase = TransferAlignmentPhase.CONVERGED
                }
            }
            else -> Unit
        }
        return status(reference, phone)
    }

    fun result(reference: MasterInsReference, phone: NavigationSolution): TransferAlignmentResult? {
        if (phase != TransferAlignmentPhase.CONVERGED) return null
        val correction = Vec3(-state[ATTITUDE], -state[ATTITUDE + 1], -state[ATTITUDE + 2])
        val correctedBodyToNed = multiply3(
            Quaternion.fromRotationVector(correction).toMatrix(),
            bodyToNedMatrix(
                Math.toRadians(phone.eulerDeg.x), Math.toRadians(phone.eulerDeg.y), Math.toRadians(phone.eulerDeg.z),
            ),
        )
        val masterToPhone = multiply3(transpose3(reference.bodyToNed.toMatrix()), correctedBodyToNed)
        return TransferAlignmentResult(
            attitudeCorrectionNedRad = correction,
            accelerometerBiasCorrectionMps2 = Vec3.from(state, ACCEL_BIAS),
            gyroBiasCorrectionRadps = Vec3.from(state, GYRO_BIAS),
            mountingEulerDeg = eulerFromBodyToNed(masterToPhone),
            attitudeStdDeg = attitudeStdDeg(),
            accelerometerBiasStdMps2 = stateStd(ACCEL_BIAS),
            gyroBiasStdRadps = stateStd(GYRO_BIAS),
        )
    }

    private fun propagate(phone: NavigationSolution, dt: Double) {
        val f = DoubleArray(STATE_SIZE * STATE_SIZE)
        val bodyToNed = bodyToNedMatrix(
            Math.toRadians(phone.eulerDeg.x), Math.toRadians(phone.eulerDeg.y), Math.toRadians(phone.eulerDeg.z),
        )
        val specificForceBody = phone.latestImu?.accelerationMps2?.minus(phone.accelerometerBiasMps2) ?: Vec3.ZERO
        val specificForceNed = multiply3(bodyToNed, specificForceBody)
        val forceSkew = skew(specificForceNed)
        for (row in 0..2) for (col in 0..2) {
            f[index(VELOCITY + row, ATTITUDE + col)] = -forceSkew[row * 3 + col]
            f[index(VELOCITY + row, ACCEL_BIAS + col)] = bodyToNed[row * 3 + col]
            f[index(ATTITUDE + row, GYRO_BIAS + col)] = -bodyToNed[row * 3 + col]
        }
        val phi = identity()
        for (i in phi.indices) phi[i] += f[i] * dt
        state = multiplyVector(phi, state)
        covariance = multiply(multiply(phi, covariance), transpose(phi))
        val q = doubleArrayOf(0.03, 0.03, 0.05, 0.002, 0.002, 0.004, 0.0002, 0.0002, 0.0002, 0.002, 0.002, 0.002)
        for (i in 0 until STATE_SIZE) covariance[index(i, i)] += q[i] * q[i] * dt
        symmetrize()
    }

    private fun updateVelocity(measurement: Vec3, suppliedStd: Vec3) {
        val std = doubleArrayOf(suppliedStd.x, suppliedStd.y, suppliedStd.z).map { it.takeIf(Double::isFinite)?.coerceIn(0.05, 2.0) ?: 0.5 }
        totalUpdates++
        var nis = 0.0
        for (axis in 0..2) {
            val innovation = measurement.toArray()[axis] - state[VELOCITY + axis]
            val innovationVariance = covariance[index(VELOCITY + axis, VELOCITY + axis)] + std[axis] * std[axis]
            nis += innovation * innovation / innovationVariance
        }
        if (nis > NIS_99_3DOF) return
        acceptedUpdates++
        // Track the post-estimate innovation, not the raw measurement: the main dead-reckoning
        // velocity legitimately diverges while GNSS is withheld during alignment, so the raw
        // residual grows even as the estimator converges. The innovation shrinks instead.
        val innovationVec = measurement - Vec3.from(state, VELOCITY)
        squaredResidualSum += innovationVec.normSquared()
        residualSamples++
        for (axis in 0..2) scalarJosephUpdate(VELOCITY + axis, measurement.toArray()[axis], std[axis])
    }

    private fun scalarJosephUpdate(measurementState: Int, measurement: Double, noiseStd: Double) {
        val innovation = measurement - state[measurementState]
        val variance = noiseStd * noiseStd
        val innovationVariance = covariance[index(measurementState, measurementState)] + variance
        val gain = DoubleArray(STATE_SIZE) { covariance[index(it, measurementState)] / innovationVariance }
        for (i in 0 until STATE_SIZE) state[i] += gain[i] * innovation
        val ikh = identity()
        for (row in 0 until STATE_SIZE) ikh[index(row, measurementState)] -= gain[row]
        val joseph = multiply(multiply(ikh, covariance), transpose(ikh))
        for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
            joseph[index(row, col)] += gain[row] * variance * gain[col]
        }
        covariance = joseph
        symmetrize()
    }

    private fun converged(): Boolean {
        if (totalUpdates < 50 || acceptedUpdates.toDouble() / totalUpdates < 0.9) return false
        val std = attitudeStdDeg()
        // Manual Euler angles are authoritative, so require the manoeuvre to reduce
        // yaw uncertainty from its 30° prior while keeping leveling well bounded.
        if (std.x > 2.0 || std.y > 3.0 || std.z > 28.0) return false
        if (residualRms() > 0.5) return false
        val oldest = angleHistory.firstOrNull()?.second ?: return false
        val newest = angleHistory.lastOrNull()?.second ?: return false
        return Math.toDegrees((newest - oldest).norm()) <= 0.25
    }

    private fun status(reference: MasterInsReference, phone: NavigationSolution): TransferAlignmentStatus {
        val mount = if (phase == TransferAlignmentPhase.CONVERGED) {
            result(reference, phone)?.mountingEulerDeg ?: Vec3.ZERO
        } else Vec3(
            Math.toDegrees(-state[ATTITUDE]), Math.toDegrees(-state[ATTITUDE + 1]), Math.toDegrees(-state[ATTITUDE + 2]),
        )
        return TransferAlignmentStatus(
            phase = phase,
            mountingEulerDeg = mount,
            message = when (phase) {
                TransferAlignmentPhase.STRAIGHT_1 -> "Drive straight and steadily"
                TransferAlignmentPhase.MANEUVER -> "Perform a turn of at least 20°"
                TransferAlignmentPhase.STRAIGHT_2 -> "Drive straight again while alignment converges"
                TransferAlignmentPhase.CONVERGED -> "Transfer alignment converged"
                else -> "Waiting for master INS"
            },
        )
    }

    private fun updateStraightTimer(stable: Boolean, now: Long) {
        if (!stable) straightStartNanos = Long.MIN_VALUE else if (straightStartNanos == Long.MIN_VALUE) straightStartNanos = now
    }

    private fun straightDuration(now: Long) = if (straightStartNanos == Long.MIN_VALUE) 0L else now - straightStartNanos

    private fun recordAngle(now: Long) {
        val angle = Vec3(state[ATTITUDE], state[ATTITUDE + 1], state[ATTITUDE + 2])
        angleHistory.addLast(now to angle)
        while (angleHistory.isNotEmpty() && now - angleHistory.first().first > 2_000_000_000L) angleHistory.removeFirst()
    }

    private fun residualRms() = if (residualSamples == 0) Double.NaN else sqrt(squaredResidualSum / residualSamples)
    private fun attitudeStdDeg() = stateStd(ATTITUDE) * (180.0 / Math.PI)
    private fun stateStd(offset: Int) = Vec3(
        sqrt(covariance[index(offset, offset)].coerceAtLeast(0.0)),
        sqrt(covariance[index(offset + 1, offset + 1)].coerceAtLeast(0.0)),
        sqrt(covariance[index(offset + 2, offset + 2)].coerceAtLeast(0.0)),
    )

    private fun resetCovariance() {
        covariance.fill(0.0)
        val std = doubleArrayOf(3.0, 3.0, 3.0, Math.toRadians(10.0), Math.toRadians(10.0), Math.toRadians(30.0),
            0.03, 0.03, 0.03, 0.4, 0.4, 0.4)
        for (i in 0 until STATE_SIZE) covariance[index(i, i)] = std[i] * std[i]
    }

    private fun identity() = DoubleArray(STATE_SIZE * STATE_SIZE) { if (it / STATE_SIZE == it % STATE_SIZE) 1.0 else 0.0 }
    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(STATE_SIZE * STATE_SIZE)
        for (r in 0 until STATE_SIZE) for (k in 0 until STATE_SIZE) {
            val value = a[index(r, k)]
            if (value != 0.0) for (c in 0 until STATE_SIZE) out[index(r, c)] += value * b[index(k, c)]
        }
        return out
    }
    private fun multiplyVector(a: DoubleArray, x: DoubleArray): DoubleArray {
        require(x.size == STATE_SIZE)
        return DoubleArray(STATE_SIZE) { r -> (0 until STATE_SIZE).sumOf { c -> a[index(r, c)] * x[c] } }
    }
    private fun transpose(a: DoubleArray) = DoubleArray(a.size) { index -> a[(index % STATE_SIZE) * STATE_SIZE + index / STATE_SIZE] }
    private fun symmetrize() {
        for (r in 0 until STATE_SIZE) for (c in r + 1 until STATE_SIZE) {
            val value = (covariance[index(r, c)] + covariance[index(c, r)]) * 0.5
            covariance[index(r, c)] = value; covariance[index(c, r)] = value
        }
    }
    private fun index(row: Int, col: Int) = row * STATE_SIZE + col
    private fun skew(v: Vec3) = doubleArrayOf(0.0, -v.z, v.y, v.z, 0.0, -v.x, -v.y, v.x, 0.0)
    private fun Vec3.cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    private fun Vec3.normSquared() = x * x + y * y + z * z

    companion object {
        private const val STATE_SIZE = 12
        private const val VELOCITY = 0
        private const val ATTITUDE = 3
        private const val GYRO_BIAS = 6
        private const val ACCEL_BIAS = 9
        private const val MIN_SPEED_MPS = 3.0
        private const val MAX_STRAIGHT_ACCEL_MPS2 = 0.5
        private val MAX_STRAIGHT_YAW_RATE_RADPS = Math.toRadians(2.0)
        private val REQUIRED_HEADING_CHANGE_RAD = Math.toRadians(20.0)
        private const val REQUIRED_STRAIGHT_NANOS = 5_000_000_000L
        private const val NIS_99_3DOF = 11.344866730144373
    }
}
