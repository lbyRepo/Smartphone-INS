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
    private val recentImu = ArrayDeque<TimedImuSample>()
    private var imuStationary = false
    private var imuClearlyMoving = false
    private var gnssStationaryUntilNanos = Long.MIN_VALUE
    private var consecutiveStationaryGnssFixes = 0
    private var stationaryConstrained = false
    private var stationaryAnchorEcef: Vec3? = null
    private var stationaryAnchorSamples = 0
    private var lastGnssPositionUpdateNanos = Long.MIN_VALUE
    private var lastGnssVelocityUpdateNanos = Long.MIN_VALUE
    private var lastGnssUpdateRejected = false
    private var gnssAidingSuspended = false
    private var rejectedPositionCandidate: Vec3? = null
    private var rejectedPositionCandidateCount = 0
    private var rejectedVelocityCandidate: Vec3? = null
    private var rejectedVelocityCandidateCount = 0

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
        recentImu.clear()
        imuStationary = false
        imuClearlyMoving = false
        gnssStationaryUntilNanos = Long.MIN_VALUE
        consecutiveStationaryGnssFixes = 0
        stationaryConstrained = false
        stationaryAnchorEcef = null
        stationaryAnchorSamples = 0
        lastGnssPositionUpdateNanos = Long.MIN_VALUE
        lastGnssVelocityUpdateNanos = Long.MIN_VALUE
        lastGnssUpdateRejected = false
        gnssAidingSuspended = false
        rejectedPositionCandidate = null
        rejectedPositionCandidateCount = 0
        rejectedVelocityCandidate = null
        rejectedVelocityCandidateCount = 0
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
        stationaryAnchorEcef = null
        stationaryAnchorSamples = 0
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
        require(gnss.hasAltitude && gnss.altitudeEllipsoidM.isFinite()) { "Automatic initialization requires a valid 3D GNSS fix" }
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
        stationaryAnchorEcef = positionEcef
        stationaryAnchorSamples = 1
        lastGnssPositionUpdateNanos = gnss.monotonicNanos
        if (gnss.hasHorizontalVelocity) lastGnssVelocityUpdateNanos = gnss.monotonicNanos
        resetCovariance()
    }

    @Synchronized
    fun initializeReplay(request: ReplayInitialization) {
        require(request.latitudeDeg in -90.0..90.0) { "Latitude must be between -90 and 90 degrees" }
        require(request.longitudeDeg in -180.0..180.0) { "Longitude must be between -180 and 180 degrees" }
        require(request.altitudeEllipsoidM in -1_000.0..100_000.0) { "Altitude is outside the supported range" }
        require(request.eulerDeg.y in -90.0..90.0) { "Pitch must be between -90 and 90 degrees" }

        profile = request.motionProfile
        positionEcef = geodeticToEcef(request.latitudeDeg, request.longitudeDeg, request.altitudeEllipsoidM)
        val geo = ecefToGeodetic(positionEcef)
        val nedToEcef = transpose3(ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad))
        velocityEcef = multiply3(nedToEcef, request.velocityNedMps)
        bodyToEcef = Quaternion.fromMatrix(
            multiply3(
                nedToEcef,
                bodyToNedMatrix(
                    Math.toRadians(request.eulerDeg.x),
                    Math.toRadians(request.eulerDeg.y),
                    Math.toRadians(((request.eulerDeg.z % 360.0) + 360.0) % 360.0),
                ),
            ),
        )
        accelerometerBias = request.accelerometerBiasMps2
        gyroBias = request.gyroBiasRadps
        lastImu = request.imuSample
        initSource = InitSource.REPLAY
        initializedWithMagnetometer = false
        movementLatched = false
        courseAided = false
        qualifyingCourseFixes = 0
        stationaryAnchorEcef = null
        stationaryAnchorSamples = 0
        resetCovariance()
    }

    @Synchronized
    fun processImu(sample: TimedImuSample) {
        updateImuStationarity(sample)
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
        stabilizeTiltFromGravity(bodySpecificForce, angularRate)
        applyStationaryConstraint(sample)
    }

    @Synchronized
    fun processGnss(fix: GnssFix) {
        if (!validFix(fix)) return
        val resumingSuspendedAiding = gnssAidingSuspended
        gnssAidingSuspended = false
        val previousFix = lastGnss
        lastGnss = fix
        if (initSource == InitSource.NONE) return

        val aidingAltitude = if (fix.hasAltitude && fix.altitudeEllipsoidM.isFinite()) {
            fix.altitudeEllipsoidM
        } else {
            ecefToGeodetic(positionEcef).altitudeM
        }
        val measuredPosition = geodeticToEcef(fix.latitudeDeg, fix.longitudeDeg, aidingAltitude)
        val geo = ecefToGeodetic(measuredPosition)
        val ecefToNed = ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad)
        val nedToEcef = transpose3(ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad))
        val horizontalNoise = fix.horizontalAccuracyM.coerceIn(0.5, 100.0)
        val verticalNoise = fix.verticalAccuracyM.takeIf { it.isFinite() }?.coerceIn(1.0, 150.0) ?: 50.0
        val positionAxisMask = updateNedMeasurement(
            multiply3(ecefToNed, measuredPosition - positionEcef),
            POSITION,
            ecefToNed,
            doubleArrayOf(horizontalNoise, horizontalNoise, verticalNoise),
            correctAttitude = false,
        )
        val requiredPositionMask = if (fix.hasAltitude) (AXIS_N or AXIS_E or AXIS_D) else (AXIS_N or AXIS_E)
        var positionAccepted = (positionAxisMask and requiredPositionMask) == requiredPositionMask
        if (positionAccepted) {
            lastGnssPositionUpdateNanos = fix.monotonicNanos
            rejectedPositionCandidate = null
            rejectedPositionCandidateCount = 0
        } else if (resumingSuspendedAiding || positionConsistentWithPreviousFix(fix, previousFix, measuredPosition) ||
            trackConsistentRejectedPosition(measuredPosition, horizontalNoise)
        ) {
            reanchorPosition(measuredPosition, horizontalNoise, verticalNoise)
            lastGnssPositionUpdateNanos = fix.monotonicNanos
            positionAccepted = true
        }
        if (positionAccepted) enforcePositionAccuracyBound(measuredPosition, ecefToNed, nedToEcef, horizontalNoise, verticalNoise)

        var velocityAccepted = false
        val horizontalSpeed = hypot(fix.velocityNedMps.x, fix.velocityNedMps.y)
        val velocityUsable = fix.hasHorizontalVelocity && fix.velocityNedMps.x.isFinite() &&
            fix.velocityNedMps.y.isFinite() && horizontalSpeed <= MAX_GNSS_SPEED_MPS
        if (velocityUsable) {
            val measuredVelocity = multiply3(nedToEcef, fix.velocityNedMps)
            val speedNoise = fix.speedAccuracyMps.takeIf { it.isFinite() }?.coerceIn(0.1, 10.0) ?: 2.0
            val downNoise = if (fix.hasDerivedDownVelocity) max(1.5, speedNoise * 3) else 50.0
            val velocityAxisMask = updateNedMeasurement(
                multiply3(ecefToNed, measuredVelocity - velocityEcef),
                VELOCITY,
                ecefToNed,
                doubleArrayOf(speedNoise, speedNoise, downNoise),
                correctAttitude = false,
            )
            velocityAccepted = (velocityAxisMask and (AXIS_N or AXIS_E)) == (AXIS_N or AXIS_E)
            if (velocityAccepted) {
                lastGnssVelocityUpdateNanos = fix.monotonicNanos
                rejectedVelocityCandidate = null
                rejectedVelocityCandidateCount = 0
            } else if (resumingSuspendedAiding || velocityConsistentWithPreviousFix(fix, previousFix, measuredVelocity, speedNoise) ||
                trackConsistentRejectedVelocity(measuredVelocity, speedNoise)
            ) {
                reanchorVelocity(measuredVelocity, speedNoise, downNoise)
                lastGnssVelocityUpdateNanos = fix.monotonicNanos
                velocityAccepted = true
            }
            if (velocityAccepted) enforceVelocityAccuracyBound(fix, ecefToNed, nedToEcef, speedNoise, downNoise)
        }

        updateGnssStationarity(fix, previousFix, measuredPosition)
        if (isStationaryAt(fix.monotonicNanos)) {
            if (positionAccepted) updateStationaryAnchor(measuredPosition, horizontalNoise)
            applyTrustedZeroVelocity()
            lastGnssVelocityUpdateNanos = fix.monotonicNanos
            velocityAccepted = true
        }
        lastGnssUpdateRejected = !positionAccepted || (velocityUsable && !velocityAccepted)
        updateCourseAiding(fix)
    }

    /** Records a fresh raw fix for display while intentionally withholding all filter corrections. */
    @Synchronized
    fun observeGnssWithoutAiding(fix: GnssFix) {
        if (!validFix(fix)) return
        val previousFix = lastGnss
        lastGnss = fix
        gnssAidingSuspended = true
        lastGnssUpdateRejected = false
        courseAided = false
        val altitude = if (fix.hasAltitude && fix.altitudeEllipsoidM.isFinite()) fix.altitudeEllipsoidM else ecefToGeodetic(positionEcef).altitudeM
        updateGnssStationarity(fix, previousFix, geodeticToEcef(fix.latitudeDeg, fix.longitudeDeg, altitude))
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
        flags = flags or if (imuFresh) QualityFlag.IMU_FRESH else QualityFlag.IMU_HELD
        val positionAided = monotonicNanos - lastGnssPositionUpdateNanos in 0..GNSS_STALE_NANOS
        val velocityAided = monotonicNanos - lastGnssVelocityUpdateNanos in 0..GNSS_STALE_NANOS
        if (!positionAided) flags = flags or QualityFlag.DEGRADED or QualityFlag.DEAD_RECKONING
        if (positionAided) flags = flags or QualityFlag.GNSS_POSITION_VALID
        if (velocityAided) flags = flags or QualityFlag.GNSS_HORIZONTAL_VELOCITY_VALID
        if (velocityAided && lastGnss?.hasDerivedDownVelocity == true) flags = flags or QualityFlag.GNSS_DOWN_VELOCITY_VALID
        if (gnssFresh) flags = flags or QualityFlag.GNSS_FRESH
        if (courseAided) flags = flags or QualityFlag.HEADING_COURSE_AIDED
        if (initSource == InitSource.MANUAL) flags = flags or QualityFlag.MANUAL_INITIALIZATION
        if (initializedWithMagnetometer) flags = flags or QualityFlag.MAGNETOMETER_INITIALIZATION
        if (lastOutputOverrun) flags = flags or QualityFlag.OUTPUT_OVERRUN
        if (lastGnss?.isMock == true) flags = flags or QualityFlag.MOCK_GNSS
        if (stationaryConstrained && isStationaryAt(monotonicNanos)) flags = flags or QualityFlag.STATIONARY_CONSTRAINT
        if (gnssFresh && lastGnssUpdateRejected) flags = flags or QualityFlag.GNSS_UPDATE_REJECTED
        if (gnssFresh && gnssAidingSuspended) flags = flags or QualityFlag.GNSS_AIDING_SUSPENDED

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
            accelerometerBiasMps2 = accelerometerBias,
            gyroBiasRadps = gyroBias,
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

    private fun updateVectorMeasurement(
        measured: Vec3,
        predicted: Vec3,
        stateOffset: Int,
        noiseStd: DoubleArray,
        gateSigma: Double = 6.0,
    ): Int {
        val residual = measured - predicted
        val residuals = residual.toArray()
        var acceptedMask = 0
        for (axis in 0..2) {
            val measurementState = stateOffset + axis
            val variance = noiseStd[axis] * noiseStd[axis]
            val innovationVariance = covariance[index(measurementState, measurementState)] + variance
            if (residuals[axis] * residuals[axis] > gateSigma * gateSigma * innovationVariance) continue
            val gain = DoubleArray(STATE_SIZE) { covariance[index(it, measurementState)] / innovationVariance }
            val correction = DoubleArray(STATE_SIZE) { gain[it] * residuals[axis] }
            val oldRow = DoubleArray(STATE_SIZE) { covariance[index(measurementState, it)] }
            for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
                covariance[index(row, col)] -= gain[row] * oldRow[col]
            }
            applyError(correction)
            acceptedMask = acceptedMask or (1 shl axis)
        }
        symmetrizeCovariance()
        return acceptedMask
    }

    /** Applies a vector measurement whose uncertainties are defined in local NED, not ECEF axes. */
    private fun updateNedMeasurement(
        residualNed: Vec3,
        stateOffset: Int,
        ecefToNed: DoubleArray,
        noiseStd: DoubleArray,
        gateSigma: Double = 6.0,
        correctAttitude: Boolean = true,
    ): Int {
        val residuals = residualNed.toArray()
        var acceptedMask = 0
        for (axis in 0..2) {
            val h = DoubleArray(STATE_SIZE)
            for (component in 0..2) h[stateOffset + component] = ecefToNed[axis * 3 + component]
            var innovationVariance = noiseStd[axis] * noiseStd[axis]
            for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
                innovationVariance += h[row] * covariance[index(row, col)] * h[col]
            }
            if (residuals[axis] * residuals[axis] > gateSigma * gateSigma * innovationVariance) continue
            val gain = DoubleArray(STATE_SIZE)
            for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
                gain[row] += covariance[index(row, col)] * h[col] / innovationVariance
            }
            if (!correctAttitude) for (attitudeAxis in 0..2) gain[ATTITUDE + attitudeAxis] = 0.0
            val projectedCovariance = DoubleArray(STATE_SIZE)
            for (col in 0 until STATE_SIZE) for (row in 0 until STATE_SIZE) {
                projectedCovariance[col] += h[row] * covariance[index(row, col)]
            }
            for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
                covariance[index(row, col)] -= gain[row] * projectedCovariance[col]
            }
            applyError(DoubleArray(STATE_SIZE) { gain[it] * residuals[axis] })
            acceptedMask = acceptedMask or (1 shl axis)
        }
        if (!correctAttitude && acceptedMask != 0) clearAttitudeCrossCovariance()
        symmetrizeCovariance()
        return acceptedMask
    }

    private fun applyError(error: DoubleArray) {
        positionEcef += Vec3.from(error, POSITION)
        velocityEcef += Vec3.from(error, VELOCITY)
        bodyToEcef = (Quaternion.fromRotationVector(Vec3.from(error, ATTITUDE)) * bodyToEcef).normalized()
        accelerometerBias += Vec3.from(error, ACCEL_BIAS)
        gyroBias += Vec3.from(error, GYRO_BIAS)
    }

    private fun updateCourseAiding(fix: GnssFix) {
        if (!fix.hasHorizontalVelocity || !fix.velocityNedMps.x.isFinite() || !fix.velocityNedMps.y.isFinite()) {
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

    private fun validFix(fix: GnssFix): Boolean =
        fix.latitudeDeg.isFinite() && fix.latitudeDeg in -90.0..90.0 &&
            fix.longitudeDeg.isFinite() && fix.longitudeDeg in -180.0..180.0 &&
            fix.horizontalAccuracyM.isFinite() && fix.horizontalAccuracyM in 0.0..200.0 &&
            (!fix.hasAltitude || fix.altitudeEllipsoidM.isFinite())

    private fun updateImuStationarity(sample: TimedImuSample) {
        recentImu.addLast(sample)
        while (recentImu.isNotEmpty() && sample.monotonicNanos - recentImu.first().monotonicNanos > STATIONARY_IMU_WINDOW_NANOS) {
            recentImu.removeFirst()
        }
        if (recentImu.size < 30 || sample.monotonicNanos - recentImu.first().monotonicNanos < 600_000_000L) {
            imuStationary = false
            imuClearlyMoving = false
            return
        }
        val meanAcceleration = recentImu.map { it.accelerationMps2 }.averageVec()
        val meanAngularRate = recentImu.map { it.angularRateRadps }.averageVec()
        val accelerationRms = sqrt(recentImu.sumOf { (it.accelerationMps2 - meanAcceleration).norm().pow(2) } / recentImu.size)
        val angularRateRms = sqrt(recentImu.sumOf { (it.angularRateRadps - meanAngularRate).norm().pow(2) } / recentImu.size)
        imuStationary = abs(meanAcceleration.norm() - STANDARD_GRAVITY) < 0.7 &&
            accelerationRms < 0.22 && meanAngularRate.norm() < 0.08 && angularRateRms < 0.012
        imuClearlyMoving = abs(meanAcceleration.norm() - STANDARD_GRAVITY) > 2.0 || accelerationRms > 1.2 ||
            meanAngularRate.norm() > 0.25 || angularRateRms > 0.12
    }

    private fun updateGnssStationarity(fix: GnssFix, previous: GnssFix?, measuredPosition: Vec3) {
        val speed = hypot(fix.velocityNedMps.x, fix.velocityNedMps.y)
        val reliableLowSpeed = gnssReportsStationary(fix)
        val stablePositionWithoutVelocity = !fix.hasHorizontalVelocity && previous != null &&
            fix.monotonicNanos > previous.monotonicNanos &&
            (measuredPosition - geodeticToEcef(
                previous.latitudeDeg,
                previous.longitudeDeg,
                if (previous.hasAltitude && previous.altitudeEllipsoidM.isFinite()) previous.altitudeEllipsoidM else ecefToGeodetic(positionEcef).altitudeM,
            )).norm() <= max(5.0, 2.0 * (fix.horizontalAccuracyM + previous.horizontalAccuracyM))
        if (reliableLowSpeed || stablePositionWithoutVelocity) {
            consecutiveStationaryGnssFixes++
            if (consecutiveStationaryGnssFixes >= STATIONARY_GNSS_FIXES_REQUIRED) {
                gnssStationaryUntilNanos = fix.monotonicNanos + STATIONARY_GNSS_HOLD_NANOS
            }
        } else if (fix.hasHorizontalVelocity && speed >= STATIONARY_SPEED_MPS) {
            consecutiveStationaryGnssFixes = 0
            gnssStationaryUntilNanos = Long.MIN_VALUE
            stationaryAnchorEcef = null
            stationaryAnchorSamples = 0
        }
    }

    private fun isStationaryAt(monotonicNanos: Long) = !imuClearlyMoving && monotonicNanos <= gnssStationaryUntilNanos

    private fun gnssReportsStationary(fix: GnssFix): Boolean {
        val speed = hypot(fix.velocityNedMps.x, fix.velocityNedMps.y)
        return fix.hasHorizontalVelocity && speed < STATIONARY_SPEED_MPS &&
            (!fix.speedAccuracyMps.isFinite() || fix.speedAccuracyMps <= 1.5)
    }

    private fun applyStationaryConstraint(sample: TimedImuSample) {
        stationaryConstrained = false
        if (!isStationaryAt(sample.monotonicNanos)) return
        val meanAcceleration = recentImu.map { it.accelerationMps2 }.averageVec()
        val meanAngularRate = recentImu.map { it.angularRateRadps }.averageVec()
        val expectedSpecificForceBody = multiply3(
            transpose3(bodyToEcef.toMatrix()),
            gravityEcef(positionEcef) * -1.0,
        )
        if (imuStationary) {
            accelerometerBias = accelerometerBias * (1.0 - STATIONARY_BIAS_ALPHA) +
                (meanAcceleration - expectedSpecificForceBody) * STATIONARY_BIAS_ALPHA
            gyroBias = gyroBias * (1.0 - STATIONARY_BIAS_ALPHA) + meanAngularRate * STATIONARY_BIAS_ALPHA
        }
        applyTrustedZeroVelocity()
        stationaryAnchorEcef?.let { anchor ->
            val geo = ecefToGeodetic(anchor)
            val ecefToNed = ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad)
            updateNedMeasurement(
                multiply3(ecefToNed, anchor - positionEcef),
                POSITION,
                ecefToNed,
                doubleArrayOf(0.25, 0.25, 0.5),
                gateSigma = Double.POSITIVE_INFINITY,
                correctAttitude = false,
            )
        }
        stationaryConstrained = true
    }

    private fun stabilizeTiltFromGravity(specificForceBody: Vec3, angularRateBody: Vec3) {
        if (initSource == InitSource.NONE) return
        val forceNorm = specificForceBody.norm()
        if (forceNorm !in 7.0..12.5) return
        val horizontalAngularRate = hypot(angularRateBody.x, angularRateBody.y)
        val alpha = when {
            imuStationary -> 0.05
            abs(forceNorm - STANDARD_GRAVITY) < 0.8 && horizontalAngularRate < 0.08 -> 0.015
            abs(forceNorm - STANDARD_GRAVITY) < 1.5 && horizontalAngularRate < 0.20 -> 0.003
            else -> return
        }
        val measuredPitch = asin((specificForceBody.x / forceNorm).coerceIn(-1.0, 1.0))
        val measuredRoll = atan2(-specificForceBody.y, -specificForceBody.z)
        val geo = ecefToGeodetic(positionEcef)
        val ecefToNed = ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad)
        val current = eulerFromBodyToNed(multiply3(ecefToNed, bodyToEcef.toMatrix()))
        val currentRoll = Math.toRadians(current.x)
        val currentPitch = Math.toRadians(current.y)
        val correctedRoll = currentRoll + wrapRadians(measuredRoll - currentRoll) * alpha
        val correctedPitch = currentPitch + wrapRadians(measuredPitch - currentPitch) * alpha
        bodyToEcef = Quaternion.fromMatrix(
            multiply3(
                transpose3(ecefToNed),
                bodyToNedMatrix(correctedRoll, correctedPitch, Math.toRadians(current.z)),
            ),
        )
    }

    private fun applyTrustedZeroVelocity() {
        val geo = ecefToGeodetic(positionEcef)
        val ecefToNed = ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad)
        updateNedMeasurement(
            multiply3(ecefToNed, velocityEcef * -1.0),
            VELOCITY,
            ecefToNed,
            doubleArrayOf(0.025, 0.025, 0.04),
            gateSigma = Double.POSITIVE_INFINITY,
            correctAttitude = false,
        )
    }

    private fun clearAttitudeCrossCovariance() {
        for (attitudeAxis in 0..2) {
            val attitudeState = ATTITUDE + attitudeAxis
            for (other in 0 until STATE_SIZE) {
                if (other !in ATTITUDE until ATTITUDE + 3) {
                    covariance[index(attitudeState, other)] = 0.0
                    covariance[index(other, attitudeState)] = 0.0
                }
            }
        }
    }

    private fun updateStationaryAnchor(measuredPosition: Vec3, horizontalNoise: Double) {
        val previous = stationaryAnchorEcef
        if (previous == null || (measuredPosition - previous).norm() > max(20.0, horizontalNoise * 6.0)) {
            stationaryAnchorEcef = measuredPosition
            stationaryAnchorSamples = 1
            return
        }
        stationaryAnchorSamples++
        val alpha = (1.0 / stationaryAnchorSamples).coerceIn(0.03, 0.2)
        stationaryAnchorEcef = previous * (1.0 - alpha) + measuredPosition * alpha
    }

    private fun trackConsistentRejectedPosition(measuredPosition: Vec3, horizontalNoise: Double): Boolean {
        val candidate = rejectedPositionCandidate
        val consistent = candidate != null && (measuredPosition - candidate).norm() <= max(15.0, horizontalNoise * 6.0)
        if (consistent) {
            rejectedPositionCandidate = candidate!! * 0.5 + measuredPosition * 0.5
            rejectedPositionCandidateCount++
        } else {
            rejectedPositionCandidate = measuredPosition
            rejectedPositionCandidateCount = 1
        }
        return rejectedPositionCandidateCount >= REACQUISITION_FIX_COUNT
    }

    private fun positionConsistentWithPreviousFix(fix: GnssFix, previous: GnssFix?, measuredPosition: Vec3): Boolean {
        if (previous == null || fix.monotonicNanos <= previous.monotonicNanos) return false
        val dt = (fix.monotonicNanos - previous.monotonicNanos) * 1e-9
        if (dt > 5.0 || !previous.latitudeDeg.isFinite() || !previous.longitudeDeg.isFinite()) return false
        val previousAltitude = if (previous.hasAltitude && previous.altitudeEllipsoidM.isFinite()) {
            previous.altitudeEllipsoidM
        } else {
            ecefToGeodetic(positionEcef).altitudeM
        }
        val previousPosition = geodeticToEcef(previous.latitudeDeg, previous.longitudeDeg, previousAltitude)
        val currentSpeed = hypot(fix.velocityNedMps.x, fix.velocityNedMps.y).takeIf { it.isFinite() } ?: 0.0
        val previousSpeed = hypot(previous.velocityNedMps.x, previous.velocityNedMps.y).takeIf { it.isFinite() } ?: 0.0
        val accuracyAllowance = 3.0 * (fix.horizontalAccuracyM + previous.horizontalAccuracyM.coerceAtLeast(0.0))
        val travelAllowance = (max(currentSpeed, previousSpeed) + 10.0) * dt
        return (measuredPosition - previousPosition).norm() <= max(20.0, accuracyAllowance + travelAllowance)
    }

    private fun velocityConsistentWithPreviousFix(
        fix: GnssFix,
        previous: GnssFix?,
        measuredVelocity: Vec3,
        speedNoise: Double,
    ): Boolean {
        if (previous == null || !previous.hasHorizontalVelocity || fix.monotonicNanos <= previous.monotonicNanos) return false
        if (fix.monotonicNanos - previous.monotonicNanos > 5_000_000_000L) return false
        if (!previous.velocityNedMps.x.isFinite() || !previous.velocityNedMps.y.isFinite()) return false
        val previousPosition = geodeticToEcef(
            previous.latitudeDeg,
            previous.longitudeDeg,
            if (previous.hasAltitude && previous.altitudeEllipsoidM.isFinite()) previous.altitudeEllipsoidM else ecefToGeodetic(positionEcef).altitudeM,
        )
        val previousGeo = ecefToGeodetic(previousPosition)
        val previousVelocity = multiply3(transpose3(ecefToNedMatrix(previousGeo.latitudeRad, previousGeo.longitudeRad)), previous.velocityNedMps)
        val dt = (fix.monotonicNanos - previous.monotonicNanos) * 1e-9
        return (measuredVelocity - previousVelocity).norm() <= max(2.5, speedNoise * 6.0 + MAX_PLAUSIBLE_GNSS_ACCELERATION_MPS2 * dt)
    }

    private fun reanchorPosition(measuredPosition: Vec3, horizontalNoise: Double, verticalNoise: Double) {
        positionEcef = rejectedPositionCandidate ?: measuredPosition
        resetStateBlockCovariance(POSITION, doubleArrayOf(horizontalNoise, horizontalNoise, verticalNoise))
        rejectedPositionCandidate = null
        rejectedPositionCandidateCount = 0
    }

    private fun trackConsistentRejectedVelocity(measuredVelocity: Vec3, speedNoise: Double): Boolean {
        val candidate = rejectedVelocityCandidate
        val consistent = candidate != null && (measuredVelocity - candidate).norm() <= max(1.0, speedNoise * 6.0)
        if (consistent) {
            rejectedVelocityCandidate = candidate!! * 0.5 + measuredVelocity * 0.5
            rejectedVelocityCandidateCount++
        } else {
            rejectedVelocityCandidate = measuredVelocity
            rejectedVelocityCandidateCount = 1
        }
        return rejectedVelocityCandidateCount >= REACQUISITION_FIX_COUNT
    }

    private fun reanchorVelocity(measuredVelocity: Vec3, horizontalNoise: Double, downNoise: Double) {
        velocityEcef = rejectedVelocityCandidate ?: measuredVelocity
        resetStateBlockCovariance(VELOCITY, doubleArrayOf(horizontalNoise, horizontalNoise, downNoise))
        rejectedVelocityCandidate = null
        rejectedVelocityCandidateCount = 0
    }

    private fun enforcePositionAccuracyBound(
        measuredPosition: Vec3,
        ecefToNed: DoubleArray,
        nedToEcef: DoubleArray,
        horizontalNoise: Double,
        verticalNoise: Double,
    ) {
        val residual = multiply3(ecefToNed, measuredPosition - positionEcef)
        val horizontalLimit = max(2.0, 3.0 * horizontalNoise)
        val verticalLimit = max(3.0, 3.0 * verticalNoise)
        val horizontalResidual = hypot(residual.x, residual.y)
        val retainedScale = if (horizontalResidual > horizontalLimit) horizontalLimit / horizontalResidual else 1.0
        val correctionNed = Vec3(
            residual.x * (1.0 - retainedScale),
            residual.y * (1.0 - retainedScale),
            residual.z - residual.z.coerceIn(-verticalLimit, verticalLimit),
        )
        positionEcef += multiply3(nedToEcef, correctionNed)
    }

    private fun enforceVelocityAccuracyBound(
        fix: GnssFix,
        ecefToNed: DoubleArray,
        nedToEcef: DoubleArray,
        horizontalNoise: Double,
        downNoise: Double,
    ) {
        val current = multiply3(ecefToNed, velocityEcef)
        val horizontalLimit = max(0.25, 3.0 * horizontalNoise)
        val northResidual = current.x - fix.velocityNedMps.x
        val eastResidual = current.y - fix.velocityNedMps.y
        val horizontalResidual = hypot(northResidual, eastResidual)
        val retainedScale = if (horizontalResidual > horizontalLimit) horizontalLimit / horizontalResidual else 1.0
        val north = fix.velocityNedMps.x + northResidual * retainedScale
        val east = fix.velocityNedMps.y + eastResidual * retainedScale
        val down = if (fix.hasDerivedDownVelocity && fix.velocityNedMps.z.isFinite()) {
            val downLimit = max(1.0, 3.0 * downNoise)
            fix.velocityNedMps.z + (current.z - fix.velocityNedMps.z).coerceIn(-downLimit, downLimit)
        } else {
            current.z
        }
        velocityEcef = multiply3(nedToEcef, Vec3(north, east, down))
    }

    private fun resetStateBlockCovariance(offset: Int, std: DoubleArray) {
        for (axis in 0..2) {
            val state = offset + axis
            for (other in 0 until STATE_SIZE) {
                covariance[index(state, other)] = 0.0
                covariance[index(other, state)] = 0.0
            }
            covariance[index(state, state)] = std[axis] * std[axis]
        }
    }

    private fun List<Vec3>.averageVec(): Vec3 =
        if (isEmpty()) Vec3.ZERO else fold(Vec3.ZERO) { sum, value -> sum + value } / size.toDouble()

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
        private const val STANDARD_GRAVITY = 9.80665
        private const val STATIONARY_IMU_WINDOW_NANOS = 1_500_000_000L
        private const val STATIONARY_GNSS_HOLD_NANOS = 2_500_000_000L
        private const val STATIONARY_SPEED_MPS = 0.5
        private const val STATIONARY_GNSS_FIXES_REQUIRED = 2
        private const val STATIONARY_BIAS_ALPHA = 0.002
        private const val REACQUISITION_FIX_COUNT = 3
        private const val MAX_GNSS_SPEED_MPS = 120.0
        private const val MAX_PLAUSIBLE_GNSS_ACCELERATION_MPS2 = 15.0
        private const val AXIS_N = 1 shl 0
        private const val AXIS_E = 1 shl 1
        private const val AXIS_D = 1 shl 2
    }
}
