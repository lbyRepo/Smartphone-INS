package com.example.phoneins.navigation

import kotlin.math.*

/**
 * Phone-grade, loosely coupled 15-state ECEF error-state navigation filter.
 * The nominal state is position, velocity and attitude. The error state also
 * estimates accelerometer and gyro bias. All methods are synchronized because
 * Android sensor, GNSS and publisher callbacks arrive on different threads.
 */
class NavigationEngine(
    initialProfile: MotionProfile = MotionProfile.VEHICLE,
) {
    private var positionEcef = Vec3.ZERO
    private var velocityEcef = Vec3.ZERO
    private var bodyToEcef = Quaternion.IDENTITY
    private var accelerometerBias = Vec3.ZERO
    private var gyroBias = Vec3.ZERO
    private var covariance = DoubleArray(STATE_SIZE * STATE_SIZE)
    private var lastImu: TimedImuSample? = null
    private var lastGnss: GnssFix? = null
    private var initSource = InitSource.NONE
    private var initializedWithMagnetometer = false
    private var movementLatched = false
    private var courseAided = false
    private var qualifyingCourseFixes = 0
    private var profile = initialProfile
    private var outputSequence = 0L
    private var lastOutputOverrun = false

    init {
        resetCovariance()
    }

    @Synchronized
    fun reset(selectedProfile: MotionProfile = profile) {
        profile = selectedProfile
        positionEcef = Vec3.ZERO
        velocityEcef = Vec3.ZERO
        bodyToEcef = Quaternion.IDENTITY
        accelerometerBias = Vec3.ZERO
        gyroBias = Vec3.ZERO
        lastImu = null
        lastGnss = null
        initSource = InitSource.NONE
        initializedWithMagnetometer = false
        movementLatched = false
        courseAided = false
        qualifyingCourseFixes = 0
        outputSequence = 0
        lastOutputOverrun = false
        resetCovariance()
    }

    @Synchronized
    fun setMotionProfile(value: MotionProfile) {
        profile = value
        qualifyingCourseFixes = 0
        courseAided = false
    }

    @Synchronized
    fun initializeManual(
        request: ManualInitialization,
        stationaryAcceleration: Vec3 = Vec3(0.0, 0.0, -9.81),
        stationaryAngularRate: Vec3 = Vec3.ZERO,
    ) {
        require(request.latitudeDeg in -90.0..90.0) { "Latitude must be between -90 and 90 degrees" }
        require(request.longitudeDeg in -180.0..180.0) { "Longitude must be between -180 and 180 degrees" }
        require(request.altitudeEllipsoidM in -1_000.0..100_000.0) { "Altitude is outside the supported range" }
        require(request.pitchDeg in -90.0..90.0) { "Pitch must be between -90 and 90 degrees" }

        positionEcef = geodeticToEcef(request.latitudeDeg, request.longitudeDeg, request.altitudeEllipsoidM)
        velocityEcef = Vec3.ZERO
        val geo = ecefToGeodetic(positionEcef)
        val nedToEcef = transpose3(ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad))
        val bodyToNed = bodyToNedMatrix(
            Math.toRadians(request.rollDeg),
            Math.toRadians(request.pitchDeg),
            Math.toRadians(((request.yawDeg % 360) + 360) % 360),
        )
        bodyToEcef = Quaternion.fromMatrix(multiply3(nedToEcef, bodyToNed))
        gyroBias = stationaryAngularRate
        accelerometerBias = stationaryAcceleration - multiply3(transpose3(bodyToEcef.toMatrix()), gravityEcef(positionEcef) * -1.0)
        initSource = InitSource.MANUAL
        initializedWithMagnetometer = false
        resetCovariance()
    }

    @Synchronized
    fun initializeAutomatic(
        gnss: GnssFix,
        stationaryAcceleration: Vec3,
        stationaryAngularRate: Vec3,
        magneticFieldBodyMicroTesla: Vec3,
        magneticDeclinationDeg: Double,
    ) {
        require(gnss.latitudeDeg in -90.0..90.0 && gnss.longitudeDeg in -180.0..180.0)
        val g = stationaryAcceleration.norm().coerceAtLeast(1e-6)
        val pitch = asin((stationaryAcceleration.x / g).coerceIn(-1.0, 1.0))
        val roll = atan2(-stationaryAcceleration.y, -stationaryAcceleration.z)
        val mx = magneticFieldBodyMicroTesla.x
        val my = magneticFieldBodyMicroTesla.y
        val mz = magneticFieldBodyMicroTesla.z
        val horizontalX = mx * cos(pitch) + mz * sin(pitch)
        val horizontalY = mx * sin(roll) * sin(pitch) + my * cos(roll) - mz * sin(roll) * cos(pitch)
        val yaw = wrapRadians(atan2(-horizontalY, horizontalX) + Math.toRadians(magneticDeclinationDeg))

        positionEcef = geodeticToEcef(gnss.latitudeDeg, gnss.longitudeDeg, gnss.altitudeEllipsoidM)
        val geo = ecefToGeodetic(positionEcef)
        val nedToEcef = transpose3(ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad))
        velocityEcef = multiply3(nedToEcef, gnss.velocityNedMps)
        bodyToEcef = Quaternion.fromMatrix(multiply3(nedToEcef, bodyToNedMatrix(roll, pitch, yaw)))
        gyroBias = stationaryAngularRate
        accelerometerBias = stationaryAcceleration - multiply3(transpose3(bodyToEcef.toMatrix()), gravityEcef(positionEcef) * -1.0)
        initSource = InitSource.AUTOMATIC
        initializedWithMagnetometer = true
        lastGnss = gnss
        resetCovariance()
    }

    @Synchronized
    fun processImu(sample: TimedImuSample) {
        val previous = lastImu
        lastImu = sample
        if (initSource == InitSource.NONE || previous == null) return
        val dt = (sample.monotonicNanos - previous.monotonicNanos) * 1e-9
        if (dt <= 0.0 || dt > 0.1) return

        val angularRate = sample.angularRateRadps - gyroBias
        bodyToEcef = (bodyToEcef * Quaternion.fromRotationVector(angularRate * dt)).normalized()
        val bodySpecificForce = sample.accelerationMps2 - accelerometerBias
        val accelerationEcef = multiply3(bodyToEcef.toMatrix(), bodySpecificForce) + gravityEcef(positionEcef)
        positionEcef = positionEcef + velocityEcef * dt + accelerationEcef * (0.5 * dt * dt)
        velocityEcef = velocityEcef + accelerationEcef * dt
        propagateCovariance(bodySpecificForce, dt)
    }

    @Synchronized
    fun processGnss(fix: GnssFix) {
        lastGnss = fix
        if (initSource == InitSource.NONE) return

        val measuredPosition = geodeticToEcef(fix.latitudeDeg, fix.longitudeDeg, fix.altitudeEllipsoidM)
        val geo = ecefToGeodetic(measuredPosition)
        val ecefToNed = ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad)
        val nedToEcef = transpose3(ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad))
        val horizontalNoise = fix.horizontalAccuracyM.coerceIn(0.5, 100.0)
        val verticalNoise = fix.verticalAccuracyM.takeIf { it.isFinite() }?.coerceIn(1.0, 150.0) ?: 50.0
        updateNedMeasurement(
            multiply3(ecefToNed, measuredPosition - positionEcef),
            POSITION,
            ecefToNed,
            doubleArrayOf(horizontalNoise, horizontalNoise, verticalNoise),
        )

        if (fix.hasHorizontalVelocity) {
            val measuredVelocity = multiply3(nedToEcef, fix.velocityNedMps)
            val speedNoise = fix.speedAccuracyMps.takeIf { it.isFinite() }?.coerceIn(0.1, 10.0) ?: 2.0
            val downNoise = if (fix.hasDerivedDownVelocity) max(1.5, speedNoise * 3) else 50.0
            updateNedMeasurement(
                multiply3(ecefToNed, measuredVelocity - velocityEcef),
                VELOCITY,
                ecefToNed,
                doubleArrayOf(speedNoise, speedNoise, downNoise),
            )
        }

        updateCourseAiding(fix)
        maybeApplyZeroVelocity(fix)
    }

    @Synchronized
    fun markOutputOverrun(value: Boolean) {
        lastOutputOverrun = value
    }

    @Synchronized
    fun snapshot(monotonicNanos: Long, unixMillis: Long): NavigationSolution {
        if (initSource == InitSource.NONE) {
            return NavigationSolution.empty(monotonicNanos, unixMillis).copy(
                latestImu = lastImu,
                latestGnss = lastGnss,
                motionProfile = profile,
                sequence = outputSequence++,
            )
        }
        val geo = ecefToGeodetic(positionEcef)
        val ecefToNed = ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad)
        val velocityNed = multiply3(ecefToNed, velocityEcef)
        val bodyToNed = multiply3(ecefToNed, bodyToEcef.toMatrix())
        val euler = eulerFromBodyToNed(bodyToNed)
        val gnssAgeNanos = lastGnss?.let { monotonicNanos - it.monotonicNanos } ?: Long.MAX_VALUE
        val gnssFresh = gnssAgeNanos in 0..GNSS_STALE_NANOS
        val imuFresh = lastImu?.let { monotonicNanos - it.monotonicNanos in 0..IMU_STALE_NANOS } == true
        val fixStatus = when {
            lastGnss == null -> FixStatus.NO_FIX
            !gnssFresh -> FixStatus.STALE
            lastGnss?.hasAltitude == true -> FixStatus.FIX_3D
            else -> FixStatus.FIX_2D
        }
        var flags = QualityFlag.INITIALIZED
        if (!gnssFresh) flags = flags or QualityFlag.DEGRADED or QualityFlag.DEAD_RECKONING
        flags = flags or if (imuFresh) QualityFlag.IMU_FRESH else QualityFlag.IMU_HELD
        if (lastGnss != null) flags = flags or QualityFlag.GNSS_POSITION_VALID
        if (lastGnss?.hasHorizontalVelocity == true) flags = flags or QualityFlag.GNSS_HORIZONTAL_VELOCITY_VALID
        if (lastGnss?.hasDerivedDownVelocity == true) flags = flags or QualityFlag.GNSS_DOWN_VELOCITY_VALID
        if (gnssFresh) flags = flags or QualityFlag.GNSS_FRESH
        if (courseAided) flags = flags or QualityFlag.HEADING_COURSE_AIDED
        if (initSource == InitSource.MANUAL) flags = flags or QualityFlag.MANUAL_INITIALIZATION
        if (initializedWithMagnetometer) flags = flags or QualityFlag.MAGNETOMETER_INITIALIZATION
        if (lastOutputOverrun) flags = flags or QualityFlag.OUTPUT_OVERRUN
        if (lastGnss?.isMock == true) flags = flags or QualityFlag.MOCK_GNSS

        val positionStd = Vec3(sqrtDiag(POSITION), sqrtDiag(POSITION + 1), sqrtDiag(POSITION + 2))
        val velocityStd = Vec3(sqrtDiag(VELOCITY), sqrtDiag(VELOCITY + 1), sqrtDiag(VELOCITY + 2))
        val attitudeStd = Vec3(sqrtDiag(ATTITUDE), sqrtDiag(ATTITUDE + 1), sqrtDiag(ATTITUDE + 2)) * (180.0 / Math.PI)
        return NavigationSolution(
            monotonicNanos = monotonicNanos,
            unixMillis = unixMillis,
            latitudeDeg = Math.toDegrees(geo.latitudeRad),
            longitudeDeg = Math.toDegrees(geo.longitudeRad),
            altitudeEllipsoidM = geo.altitudeM,
            velocityNedMps = velocityNed,
            eulerDeg = euler,
            positionStdNedM = positionStd,
            velocityStdNedMps = velocityStd,
            attitudeStdDeg = attitudeStd,
            latestImu = lastImu,
            latestGnss = lastGnss,
            initializationSource = initSource,
            motionProfile = profile,
            fixStatus = fixStatus,
            qualityFlags = flags,
            sequence = outputSequence++,
        )
    }

    private fun propagateCovariance(specificForceBody: Vec3, dt: Double) {
        val f = DoubleArray(STATE_SIZE * STATE_SIZE)
        for (i in 0..2) f[index(POSITION + i, VELOCITY + i)] = 1.0
        val r = bodyToEcef.toMatrix()
        val skew = doubleArrayOf(
            0.0, -specificForceBody.z, specificForceBody.y,
            specificForceBody.z, 0.0, -specificForceBody.x,
            -specificForceBody.y, specificForceBody.x, 0.0,
        )
        val attitudeCoupling = multiply3(r, skew)
        for (row in 0..2) for (col in 0..2) {
            f[index(VELOCITY + row, ATTITUDE + col)] = -attitudeCoupling[row * 3 + col]
            f[index(VELOCITY + row, ACCEL_BIAS + col)] = -r[row * 3 + col]
            f[index(ATTITUDE + row, GYRO_BIAS + col)] = -r[row * 3 + col]
        }
        val phi = DoubleArray(STATE_SIZE * STATE_SIZE) { i -> if (i / STATE_SIZE == i % STATE_SIZE) 1.0 else 0.0 }
        for (i in phi.indices) phi[i] += f[i] * dt
        val temp = multiply(phi, covariance)
        covariance = multiply(temp, transpose(phi))
        val noise = doubleArrayOf(0.0, 0.0, 0.0, 0.08, 0.08, 0.08, 0.004, 0.004, 0.004, 0.002, 0.002, 0.002, 0.0002, 0.0002, 0.0002)
        for (i in 0 until STATE_SIZE) covariance[index(i, i)] += noise[i] * noise[i] * dt
        symmetrizeCovariance()
    }

    private fun updateVectorMeasurement(measured: Vec3, predicted: Vec3, stateOffset: Int, noiseStd: DoubleArray) {
        val residual = measured - predicted
        val residuals = residual.toArray()
        for (axis in 0..2) {
            val measurementState = stateOffset + axis
            val variance = noiseStd[axis] * noiseStd[axis]
            val innovationVariance = covariance[index(measurementState, measurementState)] + variance
            if (residuals[axis] * residuals[axis] > 36.0 * innovationVariance) continue
            val gain = DoubleArray(STATE_SIZE) { covariance[index(it, measurementState)] / innovationVariance }
            val correction = DoubleArray(STATE_SIZE) { gain[it] * residuals[axis] }
            val oldRow = DoubleArray(STATE_SIZE) { covariance[index(measurementState, it)] }
            for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
                covariance[index(row, col)] -= gain[row] * oldRow[col]
            }
            applyError(correction)
        }
        symmetrizeCovariance()
    }

    /** Applies a vector measurement whose uncertainties are defined in local NED, not ECEF axes. */
    private fun updateNedMeasurement(residualNed: Vec3, stateOffset: Int, ecefToNed: DoubleArray, noiseStd: DoubleArray) {
        val residuals = residualNed.toArray()
        for (axis in 0..2) {
            val h = DoubleArray(STATE_SIZE)
            for (component in 0..2) h[stateOffset + component] = ecefToNed[axis * 3 + component]
            var innovationVariance = noiseStd[axis] * noiseStd[axis]
            for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
                innovationVariance += h[row] * covariance[index(row, col)] * h[col]
            }
            if (residuals[axis] * residuals[axis] > 36.0 * innovationVariance) continue
            val gain = DoubleArray(STATE_SIZE)
            for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
                gain[row] += covariance[index(row, col)] * h[col] / innovationVariance
            }
            val projectedCovariance = DoubleArray(STATE_SIZE)
            for (col in 0 until STATE_SIZE) for (row in 0 until STATE_SIZE) {
                projectedCovariance[col] += h[row] * covariance[index(row, col)]
            }
            for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
                covariance[index(row, col)] -= gain[row] * projectedCovariance[col]
            }
            applyError(DoubleArray(STATE_SIZE) { gain[it] * residuals[axis] })
        }
        symmetrizeCovariance()
    }

    private fun applyError(error: DoubleArray) {
        positionEcef += Vec3.from(error, POSITION)
        velocityEcef += Vec3.from(error, VELOCITY)
        bodyToEcef = (Quaternion.fromRotationVector(Vec3.from(error, ATTITUDE)) * bodyToEcef).normalized()
        accelerometerBias += Vec3.from(error, ACCEL_BIAS)
        gyroBias += Vec3.from(error, GYRO_BIAS)
    }

    private fun updateCourseAiding(fix: GnssFix) {
        if (!fix.hasHorizontalVelocity) {
            courseAided = false
            return
        }
        val speed = hypot(fix.velocityNedMps.x, fix.velocityNedMps.y)
        val qualified = when (profile) {
            MotionProfile.VEHICLE -> speed >= 2.0 && fix.speedAccuracyMps <= 0.75 && fix.bearingAccuracyDeg <= 15.0
            MotionProfile.PEDESTRIAN -> speed >= 1.2 && fix.speedAccuracyMps <= 0.5 && fix.bearingAccuracyDeg <= 25.0
        }
        qualifyingCourseFixes = if (qualified) qualifyingCourseFixes + 1 else 0
        val needed = if (profile == MotionProfile.VEHICLE) 2 else 3
        val disableSpeed = if (profile == MotionProfile.VEHICLE) 1.0 else 0.7
        if (speed < disableSpeed) courseAided = false
        if (qualifyingCourseFixes < needed) return

        movementLatched = true
        val geo = ecefToGeodetic(positionEcef)
        val ecefToNed = ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad)
        val currentEuler = eulerFromBodyToNed(multiply3(ecefToNed, bodyToEcef.toMatrix()))
        var course = atan2(fix.velocityNedMps.y, fix.velocityNedMps.x)
        if (course < 0) course += 2 * Math.PI
        val residual = wrapRadians(course - Math.toRadians(currentEuler.z))
        if (abs(residual) > Math.toRadians(100.0)) {
            courseAided = false
            return
        }
        val gain = (0.35 / (1.0 + fix.bearingAccuracyDeg / 10.0)).coerceIn(0.03, 0.25)
        val correctedYaw = Math.toRadians(currentEuler.z) + residual * gain
        val nedToEcef = transpose3(ecefToNed)
        bodyToEcef = Quaternion.fromMatrix(
            multiply3(
                nedToEcef,
                bodyToNedMatrix(Math.toRadians(currentEuler.x), Math.toRadians(currentEuler.y), correctedYaw),
            ),
        )
        covariance[index(ATTITUDE + 2, ATTITUDE + 2)] *= 0.8
        courseAided = true
    }

    private fun maybeApplyZeroVelocity(fix: GnssFix) {
        val imu = lastImu ?: return
        val speed = hypot(fix.velocityNedMps.x, fix.velocityNedMps.y)
        val stationary = speed < 0.35 && imu.angularRateRadps.norm() < 0.03 && abs(imu.accelerationMps2.norm() - 9.81) < 0.35
        if (stationary) updateVectorMeasurement(Vec3.ZERO, velocityEcef, VELOCITY, doubleArrayOf(0.08, 0.08, 0.12))
    }

    private fun resetCovariance() {
        covariance.fill(0.0)
        val std = doubleArrayOf(8.0, 8.0, 12.0, 1.5, 1.5, 2.0, 0.12, 0.12, 0.25, 0.4, 0.4, 0.4, 0.03, 0.03, 0.03)
        for (i in 0 until STATE_SIZE) covariance[index(i, i)] = std[i] * std[i]
    }

    private fun sqrtDiag(stateIndex: Int) = sqrt(covariance[index(stateIndex, stateIndex)].coerceAtLeast(0.0))

    private fun symmetrizeCovariance() {
        for (row in 0 until STATE_SIZE) for (col in row + 1 until STATE_SIZE) {
            val value = (covariance[index(row, col)] + covariance[index(col, row)]) / 2
            covariance[index(row, col)] = value
            covariance[index(col, row)] = value
        }
    }

    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(STATE_SIZE * STATE_SIZE)
        for (row in 0 until STATE_SIZE) for (k in 0 until STATE_SIZE) {
            val av = a[index(row, k)]
            if (av == 0.0) continue
            for (col in 0 until STATE_SIZE) out[index(row, col)] += av * b[index(k, col)]
        }
        return out
    }

    private fun transpose(a: DoubleArray): DoubleArray {
        val out = DoubleArray(a.size)
        for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) out[index(col, row)] = a[index(row, col)]
        return out
    }

    private fun index(row: Int, col: Int) = row * STATE_SIZE + col

    companion object {
        private const val STATE_SIZE = 15
        private const val POSITION = 0
        private const val VELOCITY = 3
        private const val ATTITUDE = 6
        private const val ACCEL_BIAS = 9
        private const val GYRO_BIAS = 12
        private const val GNSS_STALE_NANOS = 2_000_000_000L
        private const val IMU_STALE_NANOS = 50_000_000L
    }
}
