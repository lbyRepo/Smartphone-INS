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
    /** Causal moving-average window over raw IMU used for mechanization; < 2 disables smoothing. */
    private val imuSmoothingWindow: Int = IMU_SMOOTHING_WINDOW_SAMPLES,
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
    private var profile = initialProfile
    private var outputSequence = 0L
    private var lastOutputOverrun = false
    private val recentImu = ArrayDeque<TimedImuSample>()
    private val imuQuality = ImuQualityMonitor()
    private val accelSmoothingWindow = ArrayDeque<Vec3>()
    private val gyroSmoothingWindow = ArrayDeque<Vec3>()
    private var alignmentPhase = AlignmentPhase.NONE
    private var alignmentLeverArmFrdM = Vec3.ZERO
    private var alignmentStartNanos = Long.MIN_VALUE
    private var masterVelocityAccepted = 0
    private var masterVelocityTotal = 0
    private var lastMasterBodyToNed: Quaternion? = null
    private var mountingEulerDeg = Vec3.ZERO
    private var mountingStdDeg = Vec3(Double.NaN, Double.NaN, Double.NaN)
    private val transferEstimator = TransferAlignmentEstimator()
    private var lastTransferStatus = TransferAlignmentStatus()
    private var initializationNanos = Long.MIN_VALUE
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
    private var gnssAidingEnabled = true
    private var steadyVelocityUntilNanos = Long.MIN_VALUE
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
        outputSequence = 0
        lastOutputOverrun = false
        recentImu.clear()
        imuQuality.reset()
        accelSmoothingWindow.clear()
        gyroSmoothingWindow.clear()
        alignmentPhase = AlignmentPhase.NONE
        alignmentStartNanos = Long.MIN_VALUE
        masterVelocityAccepted = 0
        masterVelocityTotal = 0
        lastMasterBodyToNed = null
        mountingEulerDeg = Vec3.ZERO
        mountingStdDeg = Vec3(Double.NaN, Double.NaN, Double.NaN)
        initializationNanos = Long.MIN_VALUE
        transferEstimator.reset()
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
        gnssAidingEnabled = true
        steadyVelocityUntilNanos = Long.MIN_VALUE
        rejectedPositionCandidate = null
        rejectedPositionCandidateCount = 0
        rejectedVelocityCandidate = null
        rejectedVelocityCandidateCount = 0
        resetCovariance()
    }

    @Synchronized
    fun setMotionProfile(value: MotionProfile) {
        profile = value
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
        resetInitializedCovariance()
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
        // Missing velocity components are not observations. Use zero only as an
        // initial prior; never rotate NaN into the nominal ECEF state.
        val initialVelocity = Vec3(
            if (gnss.hasHorizontalVelocity) gnss.velocityNedMps.x.takeIf { it.isFinite() } ?: 0.0 else 0.0,
            if (gnss.hasHorizontalVelocity) gnss.velocityNedMps.y.takeIf { it.isFinite() } ?: 0.0 else 0.0,
            if (gnss.hasDerivedDownVelocity) gnss.velocityNedMps.z.takeIf { it.isFinite() } ?: 0.0 else 0.0,
        )
        velocityEcef = multiply3(nedToEcef, initialVelocity)
        bodyToEcef = Quaternion.fromMatrix(multiply3(nedToEcef, bodyToNedMatrix(roll, pitch, yaw)))
        gyroBias = stationaryAngularRate
        accelerometerBias = stationaryAcceleration - multiply3(transpose3(bodyToEcef.toMatrix()), gravityEcef(positionEcef) * -1.0)
        initSource = InitSource.AUTOMATIC
        initializedWithMagnetometer = true
        lastGnss = gnss
        stationaryAnchorEcef = positionEcef
        stationaryAnchorSamples = 1
        lastGnssPositionUpdateNanos = gnss.monotonicNanos
        if (gnss.hasHorizontalVelocity && gnss.velocityNedMps.x.isFinite() && gnss.velocityNedMps.y.isFinite()) {
            lastGnssVelocityUpdateNanos = gnss.monotonicNanos
        }
        resetInitializedCovariance()
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
        stationaryAnchorEcef = null
        stationaryAnchorSamples = 0
        resetInitializedCovariance()
    }

    /**
     * Starts master-INS transfer alignment. The slave is initialized from the first master
     * reference; position is master-driven, the shadow estimator matches slave velocity
     * against the master to estimate the stiff (mounting) angle, and the filter is promoted
     * to [AlignmentPhase.READY] once its covariance converges. GNSS is ignored until then.
     */
    @Synchronized
    fun beginTransferAlignment(leverArmFrdM: Vec3 = Vec3.ZERO) {
        require(initSource == InitSource.NONE) { "Reset the filter before starting master alignment" }
        alignmentLeverArmFrdM = leverArmFrdM
        alignmentPhase = AlignmentPhase.ALIGNING
        alignmentStartNanos = Long.MIN_VALUE
        masterVelocityAccepted = 0
        masterVelocityTotal = 0
        lastMasterBodyToNed = null
        mountingEulerDeg = Vec3.ZERO
        mountingStdDeg = Vec3(Double.NaN, Double.NaN, Double.NaN)
        transferEstimator.reset()
    }

    @Synchronized
    fun processMasterReference(reference: MasterInsReference) {
        if (alignmentPhase != AlignmentPhase.ALIGNING) return
        if (!reference.requiredFieldsValid) return
        if (!reference.velocityNedMps.toArray().all { it.isFinite() } ||
            !reference.latitudeDeg.isFinite() || !reference.longitudeDeg.isFinite()) return
        val masterBodyToNed = reference.bodyToNed.normalized()
        val masterPosition = geodeticToEcef(reference.latitudeDeg, reference.longitudeDeg, reference.altitudeM)

        if (initSource == InitSource.NONE) {
            // First reference initializes the slave entirely from the master pose. The
            // mounting yaw is unknown, so its prior is wide; roll/pitch mirror the master's
            // own attitude quality (a pessimistic roll/pitch prior would let gravity couple
            // into velocity uncertainty at 9.8 m/s^2 per radian and block convergence).
            val geo = ecefToGeodetic(masterPosition)
            val nedToEcef = transpose3(ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad))
            positionEcef = masterPosition
            velocityEcef = multiply3(nedToEcef, reference.velocityNedMps)
            bodyToEcef = Quaternion.fromMatrix(multiply3(nedToEcef, masterBodyToNed.toMatrix())).normalized()
            accelerometerBias = Vec3.ZERO
            gyroBias = Vec3.ZERO
            initSource = InitSource.TRANSFER
            initializedWithMagnetometer = false
            movementLatched = false
            stationaryAnchorEcef = null
            stationaryAnchorSamples = 0
            resetInitializedCovariance()
            resetEcefStateBlockFromNed(POSITION, doubleArrayOf(2.0, 2.0, 3.0))
            resetEcefStateBlockFromNed(VELOCITY, doubleArrayOf(0.5, 0.5, 0.5))
            resetEcefStateBlockFromNed(ATTITUDE, doubleArrayOf(
                Math.toRadians(reference.attitudeStdDeg.x.coerceIn(0.5, 30.0)),
                Math.toRadians(reference.attitudeStdDeg.y.coerceIn(0.5, 30.0)),
                Math.toRadians(ALIGNMENT_MASTER_YAW_PRIOR_DEG)))
            resetBodyStateBlockCovariance(ACCEL_BIAS, doubleArrayOf(0.4, 0.4, 0.4))
            resetBodyStateBlockCovariance(GYRO_BIAS, doubleArrayOf(0.03, 0.03, 0.03))
            alignmentStartNanos = reference.phoneMonotonicNanos
            lastMasterBodyToNed = masterBodyToNed
            return
        }

        // Slave position is master-owned during alignment: hard-set and keep its uncertainty
        // at master grade. Velocity remains the slave's own dead-reckoned solution — the
        // shadow estimator matches it against the master to estimate the stiff angle.
        positionEcef = masterPosition
        resetEcefStateBlockFromNed(POSITION, doubleArrayOf(2.0, 2.0, 3.0))

        val phone = buildSolution(reference.phoneMonotonicNanos, reference.masterUtcNanos / 1_000_000L)
        val status = transferEstimator.update(reference, phone)
        lastTransferStatus = status
        mountingEulerDeg = status.mountingEulerDeg
        if (status.phase == TransferAlignmentPhase.CONVERGED) {
            transferEstimator.result(reference, phone)?.let(::applyTransferAlignment)
            alignmentPhase = AlignmentPhase.READY
        }
    }

    /** Applies the converged transfer-alignment correction to the main filter state. */
    @Synchronized
    fun applyTransferAlignment(result: TransferAlignmentResult) {
        if (initSource == InitSource.NONE) return
        val geo = ecefToGeodetic(positionEcef)
        val nedToEcef = transpose3(ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad))
        val correctionEcef = multiply3(nedToEcef, result.attitudeCorrectionNedRad)
        bodyToEcef = (Quaternion.fromRotationVector(correctionEcef) * bodyToEcef).normalized()
        accelerometerBias += result.accelerometerBiasCorrectionMps2
        gyroBias += result.gyroBiasCorrectionRadps
        resetEcefStateBlockFromNed(ATTITUDE, result.attitudeStdDeg.toArray().map { Math.toRadians(it) }.toDoubleArray())
        resetBodyStateBlockCovariance(ACCEL_BIAS, result.accelerometerBiasStdMps2.toArray())
        resetBodyStateBlockCovariance(GYRO_BIAS, result.gyroBiasStdRadps.toArray())
        mountingEulerDeg = result.mountingEulerDeg
        mountingStdDeg = result.attitudeStdDeg
    }

    @Synchronized
    fun processImu(sample: TimedImuSample) {
        val previous = lastImu
        imuQuality.observe(sample)
        if (previous != null && sample.monotonicNanos <= previous.monotonicNanos) return
        lastImu = sample
        if (!sample.fresh || !sample.accelerationMps2.toArray().all { it.isFinite() } ||
            !sample.angularRateRadps.toArray().all { it.isFinite() }) {
            recentImu.clear()
            accelSmoothingWindow.clear()
            gyroSmoothingWindow.clear()
            imuStationary = false
            imuClearlyMoving = false
            return
        }
        updateImuStationarity(sample)
        if (initSource == InitSource.NONE || previous == null || !previous.fresh) return
        val dt = (sample.monotonicNanos - previous.monotonicNanos) * 1e-9
        if (dt <= 0.0 || dt > 0.1) return

        // Mechanization uses a causal moving average of the raw samples to reduce vibration
        // noise; quality monitoring and stillness detection above deliberately see the raw
        // sample so smoothing cannot hide rough motion.
        val mechanization = smoothedForMechanization(sample)
        val angularRate = mechanization.angularRateRadps - gyroBias
        bodyToEcef = (bodyToEcef * Quaternion.fromRotationVector(angularRate * dt)).normalized()
        val bodySpecificForce = mechanization.accelerationMps2 - accelerometerBias
        val accelerationEcef = multiply3(bodyToEcef.toMatrix(), bodySpecificForce) + gravityEcef(positionEcef)
        positionEcef = positionEcef + velocityEcef * dt + accelerationEcef * (0.5 * dt * dt)
        velocityEcef = velocityEcef + accelerationEcef * dt
        propagateCovariance(bodySpecificForce, dt)
        applyStationaryConstraint(sample)
    }

    @Synchronized
    fun processGnss(fix: GnssFix) {
        // During master transfer alignment the slave is master-aided; GNSS is only accepted
        // once the alignment has been promoted to READY.
        if (alignmentPhase == AlignmentPhase.ALIGNING) return
        if (!validFix(fix)) return
        if (!gnssAidingEnabled) {
            recordRawGnss(fix)
            return
        }
        val resumingSuspendedAiding = gnssAidingSuspended
        gnssAidingSuspended = false
        val previousFix = lastGnss
        lastGnss = fix
        steadyVelocityUntilNanos = Long.MIN_VALUE
        if (!resumingSuspendedAiding && previousFix != null &&
            previousFix.hasHorizontalVelocity && fix.hasHorizontalVelocity &&
            previousFix.speedAccuracyMps in 0.0..0.5 && fix.speedAccuracyMps in 0.0..0.5) {
            val dt = (fix.monotonicNanos - previousFix.monotonicNanos) * 1e-9
            if (dt in 0.1..1.5 && (fix.velocityNedMps - previousFix.velocityNedMps).norm() / dt < 0.3) {
                steadyVelocityUntilNanos = fix.monotonicNanos + 1_200_000_000L
            }
        }
        if (initSource == InitSource.NONE) return

        // Bias-learning qualification is separate from attitude correction. The strict gate
        // gives full gains; physically plausible rough motion (real road/handlebar vibration)
        // gets slow capped adaptation; aliased or corrupted intervals hold the gains exactly
        // while position, velocity and attitude observations stay active.
        if (initializationNanos == Long.MIN_VALUE) initializationNanos = fix.monotonicNanos
        // Bias calibration is a settling phase: slow adaptation is allowed only while the
        // solution is still settling after initialization, so a converged estimate cannot
        // wander over a long run (measured: the van regressed when adaptation stayed open).
        val calibrationOpen = fix.monotonicNanos - initializationNanos <= BIAS_CALIBRATION_WINDOW_NS
        val biasLearnable = imuQuality.canLearn(fix.monotonicNanos)
        val biasAdaptive = calibrationOpen && !biasLearnable &&
            imuQuality.biasAdaptationAllowed(fix.monotonicNanos)
        // Accelerometer bias is only separable from tilt once the attitude is well known:
        // adapting it while the manoeuvre-induced attitude uncertainty is large absorbs real
        // lateral dynamics into bias and degrades outage direction fidelity. Gyro bias keeps
        // its correcting role because it is the attitude-improving channel.
        val accelBiasAdaptive = biasAdaptive && imuQuality.biasAdaptationSteady(fix.monotonicNanos)
        val accelBiasGainScale = when {
            biasLearnable -> 1.0
            accelBiasAdaptive -> ADAPTATION_ACCEL_BIAS_GAIN_SCALE
            else -> 0.0
        }
        // Gyro bias keeps its attitude-correcting role whenever the data is plausible, but at a
        // capped gain: a sustained cornering rate must not be absorbed into heading bias.
        val correctGyroBias = biasLearnable || biasAdaptive
        val gyroBiasGainScale = if (biasLearnable) 1.0 else ADAPTATION_GYRO_BIAS_GAIN_SCALE

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
        val requiredPositionMask = if (fix.hasAltitude) (AXIS_N or AXIS_E or AXIS_D) else (AXIS_N or AXIS_E)
        var positionAccepted: Boolean
        if (resumingSuspendedAiding) {
            // Reacquisition deliberately restores only the directly observed state. It must
            // not manufacture a large attitude or bias correction from an outage residual.
            reanchorPosition(measuredPosition, horizontalNoise, verticalNoise)
            positionAccepted = true
        } else {
            val positionAxisMask = updateNedMeasurement(
                multiply3(ecefToNed, measuredPosition - positionEcef),
                POSITION,
                ecefToNed,
                doubleArrayOf(horizontalNoise, horizontalNoise, verticalNoise),
                axisMask = requiredPositionMask,
                accelBiasGainScale = accelBiasGainScale,
                correctGyroBias = correctGyroBias,
                gyroBiasGainScale = gyroBiasGainScale,
            )
            positionAccepted = (positionAxisMask and requiredPositionMask) == requiredPositionMask
        }
        if (positionAccepted) {
            lastGnssPositionUpdateNanos = fix.monotonicNanos
            rejectedPositionCandidate = null
            rejectedPositionCandidateCount = 0
        } else if (positionConsistentWithPreviousFix(fix, previousFix, measuredPosition) ||
            trackConsistentRejectedPosition(measuredPosition, horizontalNoise)
        ) {
            reanchorPosition(measuredPosition, horizontalNoise, verticalNoise)
            lastGnssPositionUpdateNanos = fix.monotonicNanos
            positionAccepted = true
        }
        var velocityAccepted = false
        val horizontalSpeed = hypot(fix.velocityNedMps.x, fix.velocityNedMps.y)
        val velocityUsable = fix.hasHorizontalVelocity && fix.velocityNedMps.x.isFinite() &&
            fix.velocityNedMps.y.isFinite() && horizontalSpeed <= MAX_GNSS_SPEED_MPS
        if (velocityUsable) {
            val predictedNed = multiply3(ecefToNed, velocityEcef)
            val downUsable = fix.hasDerivedDownVelocity && fix.velocityNedMps.z.isFinite()
            val observedNed = fix.velocityNedMps.copy(z = if (downUsable) fix.velocityNedMps.z else predictedNed.z)
            val measuredVelocity = multiply3(nedToEcef, observedNed)
            val speedNoise = fix.speedAccuracyMps.takeIf { it.isFinite() }?.coerceIn(0.1, 10.0) ?: 2.0
            val downNoise = if (downUsable) max(1.5, speedNoise * 3) else 50.0
            if (resumingSuspendedAiding) {
                reanchorVelocity(measuredVelocity, speedNoise, downNoise)
                if (!downUsable) {
                    val reanchoredNed = multiply3(ecefToNed, velocityEcef)
                    velocityEcef = multiply3(nedToEcef, reanchoredNed.copy(z = predictedNed.z))
                }
                velocityAccepted = true
            } else {
                val velocityAxisMask = updateNedMeasurement(
                    observedNed - predictedNed,
                    VELOCITY,
                    ecefToNed,
                    doubleArrayOf(speedNoise, speedNoise, downNoise),
                    axisMask = if (downUsable) 7 else AXIS_N or AXIS_E,
                    accelBiasGainScale = accelBiasGainScale,
                    correctGyroBias = correctGyroBias,
                    gyroBiasGainScale = gyroBiasGainScale,
                )
                velocityAccepted = (velocityAxisMask and (AXIS_N or AXIS_E)) == (AXIS_N or AXIS_E)
            }
            if (velocityAccepted) {
                lastGnssVelocityUpdateNanos = fix.monotonicNanos
                rejectedVelocityCandidate = null
                rejectedVelocityCandidateCount = 0
            } else if (velocityConsistentWithPreviousFix(fix, previousFix, measuredVelocity, speedNoise) ||
                trackConsistentRejectedVelocity(measuredVelocity, speedNoise)
            ) {
                reanchorVelocity(measuredVelocity, speedNoise, downNoise)
                if (!downUsable) {
                    val reanchoredNed = multiply3(ecefToNed, velocityEcef)
                    velocityEcef = multiply3(nedToEcef, reanchoredNed.copy(z = predictedNed.z))
                }
                lastGnssVelocityUpdateNanos = fix.monotonicNanos
                velocityAccepted = true
            }
        }

        updateGnssStationarity(fix, previousFix, measuredPosition)
        if (isStationaryAt(fix.monotonicNanos)) {
            if (positionAccepted) updateStationaryAnchor(measuredPosition, horizontalNoise)
            applyTrustedZeroVelocity()
            lastGnssVelocityUpdateNanos = fix.monotonicNanos
            velocityAccepted = true
        }
        lastGnssUpdateRejected = !positionAccepted || (velocityUsable && !velocityAccepted)
        courseAided = false
    }

    /** Enables or disables every GNSS-derived correction and stationary latch. */
    @Synchronized
    fun setGnssAidingEnabled(enabled: Boolean) {
        if (gnssAidingEnabled == enabled) return
        gnssAidingEnabled = enabled
        gnssAidingSuspended = !enabled
        courseAided = false
        if (!enabled) {
            gnssStationaryUntilNanos = Long.MIN_VALUE
            consecutiveStationaryGnssFixes = 0
            stationaryConstrained = false
            stationaryAnchorEcef = null
            stationaryAnchorSamples = 0
            steadyVelocityUntilNanos = Long.MIN_VALUE
            rejectedPositionCandidate = null
            rejectedPositionCandidateCount = 0
            rejectedVelocityCandidate = null
            rejectedVelocityCandidateCount = 0
            lastGnssUpdateRejected = false
        }
    }

    /** Records a fresh raw fix for display while intentionally withholding all filter corrections. */
    @Synchronized
    fun observeGnssWithoutAiding(fix: GnssFix) {
        if (!validFix(fix)) return
        recordRawGnss(fix)
    }

    private fun recordRawGnss(fix: GnssFix) {
        lastGnss = fix
        gnssAidingSuspended = true
        lastGnssUpdateRejected = false
        courseAided = false
    }

    @Synchronized
    fun markOutputOverrun(value: Boolean) {
        lastOutputOverrun = value
    }

    @Synchronized
    fun snapshot(monotonicNanos: Long, unixMillis: Long): NavigationSolution {
        val solution = buildSolution(monotonicNanos, unixMillis)
        outputSequence++
        return solution
    }

    private fun buildSolution(monotonicNanos: Long, unixMillis: Long): NavigationSolution {
        if (initSource == InitSource.NONE) {
            return NavigationSolution.empty(monotonicNanos, unixMillis).copy(
                latestImu = lastImu,
                qualityFlags = if (lastImu?.clipped == true) QualityFlag.IMU_CLIPPED else 0,
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
        val imuFresh = lastImu?.let { it.fresh && it.accelerationMps2.toArray().all { v -> v.isFinite() } &&
            it.angularRateRadps.toArray().all { v -> v.isFinite() } && monotonicNanos - it.monotonicNanos in 0..IMU_STALE_NANOS } == true
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
        if (velocityAided && lastGnss?.let { it.hasDerivedDownVelocity && it.velocityNedMps.z.isFinite() } == true) {
            flags = flags or QualityFlag.GNSS_DOWN_VELOCITY_VALID
        }
        if (gnssFresh) flags = flags or QualityFlag.GNSS_FRESH
        if (courseAided) flags = flags or QualityFlag.HEADING_COURSE_AIDED
        if (initSource == InitSource.MANUAL) flags = flags or QualityFlag.MANUAL_INITIALIZATION
        if (initializedWithMagnetometer) flags = flags or QualityFlag.MAGNETOMETER_INITIALIZATION
        if (lastOutputOverrun) flags = flags or QualityFlag.OUTPUT_OVERRUN
        if (lastGnss?.isMock == true) flags = flags or QualityFlag.MOCK_GNSS
        if (stationaryConstrained && isStationaryAt(monotonicNanos)) flags = flags or QualityFlag.STATIONARY_CONSTRAINT
        if (gnssFresh && lastGnssUpdateRejected) flags = flags or QualityFlag.GNSS_UPDATE_REJECTED
        if (gnssFresh && gnssAidingSuspended) flags = flags or QualityFlag.GNSS_AIDING_SUSPENDED
        if (!imuQuality.canLearn(monotonicNanos)) flags = flags or QualityFlag.BIAS_LEARNING_HELD
        if (lastImu?.clipped == true) flags = flags or QualityFlag.IMU_CLIPPED

        val positionStd = projectedStandardDeviation(covariance, STATE_SIZE, POSITION, ecefToNed)
        val velocityStd = projectedStandardDeviation(covariance, STATE_SIZE, VELOCITY, ecefToNed)
        val attitudeStd = projectedStandardDeviation(
            covariance, STATE_SIZE, ATTITUDE,
            multiply3(nedRotationToEulerJacobian(Math.toRadians(euler.y), Math.toRadians(euler.z)), ecefToNed),
        ) * (180.0 / Math.PI)
        val alignmentMessage = when (alignmentPhase) {
            AlignmentPhase.READY -> "Aligned · ready (mounting yaw %.1f deg)".format(mountingEulerDeg.z)
            AlignmentPhase.ALIGNING -> lastTransferStatus.message.ifEmpty { "Waiting for master INS" }
            AlignmentPhase.NONE -> ""
        }
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
            sequence = outputSequence,
            accelerometerBiasMps2 = accelerometerBias,
            gyroBiasRadps = gyroBias,
            alignmentPhase = alignmentPhase,
            alignmentMessage = alignmentMessage,
            mountingEulerDeg = mountingEulerDeg,
            mountingStdDeg = mountingStdDeg,
        )
    }

    private fun propagateCovariance(specificForceBody: Vec3, dt: Double) {
        val f = DoubleArray(STATE_SIZE * STATE_SIZE)
        for (i in 0..2) f[index(POSITION + i, VELOCITY + i)] = 1.0
        val r = bodyToEcef.toMatrix()
        // applyError injects a LEFT ECEF attitude error: dv/dtheta = -[C f]x.
        val attitudeCoupling = ecefSpecificForceSkew(r, specificForceBody)
        for (row in 0..2) for (col in 0..2) {
            f[index(VELOCITY + row, ATTITUDE + col)] = -attitudeCoupling[row * 3 + col]
            f[index(VELOCITY + row, ACCEL_BIAS + col)] = -r[row * 3 + col]
            f[index(ATTITUDE + row, GYRO_BIAS + col)] = -r[row * 3 + col]
        }
        val phi = DoubleArray(STATE_SIZE * STATE_SIZE) { i -> if (i / STATE_SIZE == i % STATE_SIZE) 1.0 else 0.0 }
        for (i in phi.indices) phi[i] += f[i] * dt
        val temp = multiply(phi, covariance)
        covariance = multiply(temp, transpose(phi))
        // Qd = diag(noise²) * dt. Attitude entries are small-angle ECEF errors,
        // NOT Euler-angle variances. Doubling the amplitude below quadruples Qd.
        val attitudeNoise = ATTITUDE_PROCESS_NOISE_RAD_PER_SQRT_SECOND
        val accelBiasNoise = ACCEL_BIAS_PROCESS_NOISE_MPS2_PER_SQRT_SECOND
        val gyroBiasNoise = GYRO_BIAS_PROCESS_NOISE_RADPS_PER_SQRT_SECOND
        val noise = doubleArrayOf(0.0, 0.0, 0.0, 0.08, 0.08, 0.08,
            attitudeNoise, attitudeNoise, attitudeNoise,
            accelBiasNoise, accelBiasNoise, accelBiasNoise,
            gyroBiasNoise, gyroBiasNoise, gyroBiasNoise)
        for (i in 0 until STATE_SIZE) covariance[index(i, i)] += noise[i] * noise[i] * dt
        symmetrizeCovariance()
    }


    /** Applies one gated vector update whose uncertainties are defined in local NED. */
    private fun updateNedMeasurement(
        residualNed: Vec3,
        stateOffset: Int,
        ecefToNed: DoubleArray,
        noiseStd: DoubleArray,
        gateSigma: Double = Double.NaN,
        axisMask: Int = 7,
        accelBiasGainScale: Double = if (lastImu?.let { imuQuality.canLearn(it.monotonicNanos) } == true) 1.0 else 0.0,
        correctGyroBias: Boolean = accelBiasGainScale > 0.0,
        gyroBiasGainScale: Double = 1.0,
    ): Int {
        val axes = (0..2).filter { axisMask and (1 shl it) != 0 }
        val h = Array(axes.size) { row ->
            DoubleArray(STATE_SIZE).also { vector ->
                for (component in 0..2) vector[stateOffset + component] = ecefToNed[axes[row] * 3 + component]
            }
        }
        val residual = DoubleArray(axes.size) { residualNed.toArray()[axes[it]] }
        val noiseVariance = DoubleArray(axes.size) { noiseStd[axes[it]].pow(2) }
        val accepted = updateLinearMeasurement(
            residual, h, noiseVariance, gateSigma, accelBiasGainScale, correctGyroBias, gyroBiasGainScale,
        )
        return if (accepted) axisMask else 0
    }

    private fun updateLinearMeasurement(
        residual: DoubleArray,
        h: Array<DoubleArray>,
        noiseVariance: DoubleArray,
        gateSigma: Double = Double.NaN,
        accelBiasGainScale: Double = 1.0,
        correctGyroBias: Boolean = true,
        gyroBiasGainScale: Double = 1.0,
    ): Boolean {
        val m = residual.size
        if (m == 0 || residual.any { !it.isFinite() }) return false
        val s = Array(m) { DoubleArray(m) }
        for (row in 0 until m) for (col in 0 until m) {
            var value = if (row == col) noiseVariance[row] else 0.0
            for (i in 0 until STATE_SIZE) for (j in 0 until STATE_SIZE) {
                value += h[row][i] * covariance[index(i, j)] * h[col][j]
            }
            s[row][col] = value
        }
        val sInverse = invertSmallMatrix(s) ?: return false
        var nis = 0.0
        for (row in 0 until m) for (col in 0 until m) nis += residual[row] * sInverse[row][col] * residual[col]
        val gate = when {
            gateSigma == Double.POSITIVE_INFINITY -> Double.POSITIVE_INFINITY
            gateSigma.isFinite() -> gateSigma * gateSigma
            else -> NIS_999[m]
        }
        if (!nis.isFinite() || nis > gate) return false

        val gain = Array(STATE_SIZE) { DoubleArray(m) }
        for (state in 0 until STATE_SIZE) for (measurement in 0 until m) {
            for (j in 0 until STATE_SIZE) for (k in 0 until m) {
                gain[state][measurement] += covariance[index(state, j)] * h[k][j] * sInverse[k][measurement]
            }
        }
        for (state in ACCEL_BIAS until GYRO_BIAS) for (measurement in 0 until m) {
            gain[state][measurement] *= accelBiasGainScale
        }
        if (!correctGyroBias) for (state in GYRO_BIAS until STATE_SIZE) gain[state].fill(0.0)
        else if (gyroBiasGainScale != 1.0) {
            for (state in GYRO_BIAS until STATE_SIZE) {
                for (measurement in 0 until m) gain[state][measurement] *= gyroBiasGainScale
            }
        }

        val a = DoubleArray(STATE_SIZE * STATE_SIZE) { p -> if (p / STATE_SIZE == p % STATE_SIZE) 1.0 else 0.0 }
        for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
            for (measurement in 0 until m) a[index(row, col)] -= gain[row][measurement] * h[measurement][col]
        }
        val joseph = multiply(multiply(a, covariance), transpose(a))
        for (row in 0 until STATE_SIZE) for (col in 0 until STATE_SIZE) {
            for (measurement in 0 until m) {
                joseph[index(row, col)] += gain[row][measurement] * noiseVariance[measurement] * gain[col][measurement]
            }
        }
        covariance = joseph
        val correction = DoubleArray(STATE_SIZE)
        for (state in 0 until STATE_SIZE) for (measurement in 0 until m) {
            correction[state] += gain[state][measurement] * residual[measurement]
        }
        applyErrorAndReset(correction)
        return true
    }

    private fun applyErrorAndReset(error: DoubleArray) {
        positionEcef += Vec3.from(error, POSITION)
        velocityEcef += Vec3.from(error, VELOCITY)
        val attitudeError = Vec3.from(error, ATTITUDE)
        bodyToEcef = (Quaternion.fromRotationVector(attitudeError) * bodyToEcef).normalized()
        accelerometerBias += Vec3.from(error, ACCEL_BIAS)
        gyroBias += Vec3.from(error, GYRO_BIAS)
        val reset = DoubleArray(STATE_SIZE * STATE_SIZE) { p -> if (p / STATE_SIZE == p % STATE_SIZE) 1.0 else 0.0 }
        val attitudeSkew = skew3(attitudeError)
        for (row in 0..2) for (col in 0..2) {
            reset[index(ATTITUDE + row, ATTITUDE + col)] += 0.5 * attitudeSkew[row * 3 + col]
        }
        covariance = multiply(multiply(reset, covariance), transpose(reset))
        symmetrizeCovariance()
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
        if (!gnssAidingEnabled || !isStationaryAt(sample.monotonicNanos) || !imuStationary ||
            !imuQuality.canLearn(sample.monotonicNanos)) return
        val meanAcceleration = recentImu.map { it.accelerationMps2 }.averageVec()
        val meanAngularRate = recentImu.map { it.angularRateRadps }.averageVec()
        val expectedSpecificForceBody = multiply3(
            transpose3(bodyToEcef.toMatrix()),
            gravityEcef(positionEcef) * -1.0,
        )
        if (meanAngularRate.norm() < 0.025 && abs(meanAcceleration.norm() - STANDARD_GRAVITY) < 0.15) {
            val accelH = Array(3) { DoubleArray(STATE_SIZE) }
            val epsilon = 1e-6
            for (axis in 0..2) {
                val perturbation = DoubleArray(3).also { it[axis] = epsilon }
                val perturbedAttitude = (Quaternion.fromRotationVector(Vec3.from(perturbation)) * bodyToEcef).normalized()
                val perturbedExpected = multiply3(transpose3(perturbedAttitude.toMatrix()), gravityEcef(positionEcef) * -1.0)
                val derivative = (perturbedExpected - expectedSpecificForceBody) / epsilon
                accelH[0][ATTITUDE + axis] = derivative.x
                accelH[1][ATTITUDE + axis] = derivative.y
                accelH[2][ATTITUDE + axis] = derivative.z
            }
            for (axis in 0..2) accelH[axis][ACCEL_BIAS + axis] = 1.0
            // At standstill the specific-force residual cannot separate tilt from accelerometer
            // bias, so this observation corrects attitude only: the bias gain rows are held and
            // the accelerometer bias stays calibrated by initialization and by in-motion aiding.
            updateLinearMeasurement(
                (meanAcceleration - accelerometerBias - expectedSpecificForceBody).toArray(),
                accelH,
                doubleArrayOf(0.03.pow(2), 0.03.pow(2), 0.03.pow(2)),
                accelBiasGainScale = 0.0,
                correctGyroBias = false,
            )

            val gyroH = Array(3) { axis -> DoubleArray(STATE_SIZE).also { it[GYRO_BIAS + axis] = 1.0 } }
            updateLinearMeasurement(
                (meanAngularRate - gyroBias).toArray(),
                gyroH,
                doubleArrayOf(0.0002.pow(2), 0.0002.pow(2), 0.0002.pow(2)),
            )
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
            )
        }
        stationaryConstrained = true
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
        )
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
        val previousVelocity = multiply3(
            transpose3(ecefToNedMatrix(previousGeo.latitudeRad, previousGeo.longitudeRad)),
            previous.velocityNedMps.copy(z = previous.velocityNedMps.z.takeIf { previous.hasDerivedDownVelocity && it.isFinite() } ?: 0.0),
        )
        val dt = (fix.monotonicNanos - previous.monotonicNanos) * 1e-9
        return (measuredVelocity - previousVelocity).norm() <= max(2.5, speedNoise * 6.0 + MAX_PLAUSIBLE_GNSS_ACCELERATION_MPS2 * dt)
    }

    private fun reanchorPosition(measuredPosition: Vec3, horizontalNoise: Double, verticalNoise: Double) {
        positionEcef = rejectedPositionCandidate ?: measuredPosition
        resetEcefStateBlockFromNed(POSITION, doubleArrayOf(horizontalNoise, horizontalNoise, verticalNoise))
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
        resetEcefStateBlockFromNed(VELOCITY, doubleArrayOf(horizontalNoise, horizontalNoise, downNoise))
        rejectedVelocityCandidate = null
        rejectedVelocityCandidateCount = 0
    }

    private fun resetStateBlockCovariance(offset: Int, block: DoubleArray) {
        for (axis in 0..2) {
            val state = offset + axis
            for (other in 0 until STATE_SIZE) {
                covariance[index(state, other)] = 0.0
                covariance[index(other, state)] = 0.0
            }
        }
        for (row in 0..2) for (col in 0..2) covariance[index(offset + row, offset + col)] = block[row * 3 + col]
    }

    private fun resetEcefStateBlockFromNed(offset: Int, stdNed: DoubleArray) {
        val geo = ecefToGeodetic(positionEcef)
        val nedToEcef = transpose3(ecefToNedMatrix(geo.latitudeRad, geo.longitudeRad))
        val diagonalNed = DoubleArray(9)
        for (axis in 0..2) diagonalNed[axis * 3 + axis] = stdNed[axis].pow(2)
        resetStateBlockCovariance(offset, multiply3(multiply3(nedToEcef, diagonalNed), transpose3(nedToEcef)))
    }

    private fun resetBodyStateBlockCovariance(offset: Int, stdBody: DoubleArray) {
        val diagonalBody = DoubleArray(9)
        for (axis in 0..2) diagonalBody[axis * 3 + axis] = stdBody[axis].pow(2)
        resetStateBlockCovariance(offset, diagonalBody)
    }

    private fun resetInitializedCovariance() {
        resetCovariance()
        resetEcefStateBlockFromNed(POSITION, doubleArrayOf(8.0, 8.0, 12.0))
        resetEcefStateBlockFromNed(VELOCITY, doubleArrayOf(1.5, 1.5, 2.0))
        resetEcefStateBlockFromNed(ATTITUDE, doubleArrayOf(0.12, 0.12, 0.25))
        resetBodyStateBlockCovariance(ACCEL_BIAS, doubleArrayOf(0.4, 0.4, 0.4))
        resetBodyStateBlockCovariance(GYRO_BIAS, doubleArrayOf(0.03, 0.03, 0.03))
    }

    private fun List<Vec3>.averageVec(): Vec3 =
        if (isEmpty()) Vec3.ZERO else fold(Vec3.ZERO) { sum, value -> sum + value } / size.toDouble()

    private fun resetCovariance() {
        covariance.fill(0.0)
        val std = doubleArrayOf(8.0, 8.0, 12.0, 1.5, 1.5, 2.0, 0.12, 0.12, 0.25, 0.4, 0.4, 0.4, 0.03, 0.03, 0.03)
        for (i in 0 until STATE_SIZE) covariance[index(i, i)] = std[i] * std[i]
    }

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

    private fun invertSmallMatrix(matrix: Array<DoubleArray>): Array<DoubleArray>? {
        val n = matrix.size
        val augmented = Array(n) { row -> DoubleArray(2 * n).also { values ->
            for (col in 0 until n) values[col] = matrix[row][col]
            values[n + row] = 1.0
        } }
        for (pivot in 0 until n) {
            var best = pivot
            for (row in pivot + 1 until n) if (abs(augmented[row][pivot]) > abs(augmented[best][pivot])) best = row
            if (!augmented[best][pivot].isFinite() || abs(augmented[best][pivot]) < 1e-15) return null
            val swap = augmented[pivot]
            augmented[pivot] = augmented[best]
            augmented[best] = swap
            val scale = augmented[pivot][pivot]
            for (col in 0 until 2 * n) augmented[pivot][col] /= scale
            for (row in 0 until n) if (row != pivot) {
                val factor = augmented[row][pivot]
                for (col in 0 until 2 * n) augmented[row][col] -= factor * augmented[pivot][col]
            }
        }
        return Array(n) { row -> DoubleArray(n) { col -> augmented[row][n + col] } }
    }

    private fun skew3(vector: Vec3) = doubleArrayOf(
        0.0, -vector.z, vector.y,
        vector.z, 0.0, -vector.x,
        -vector.y, vector.x, 0.0,
    )

    private fun index(row: Int, col: Int) = row * STATE_SIZE + col

    /**
     * Causal moving average of the last [imuSmoothingWindow] raw IMU samples, used only for
     * mechanization. The window is skipped entirely when smoothing is disabled (< 2 samples).
     */
    private fun smoothedForMechanization(sample: TimedImuSample): TimedImuSample {
        if (imuSmoothingWindow < 2) return sample
        accelSmoothingWindow.addLast(sample.accelerationMps2)
        gyroSmoothingWindow.addLast(sample.angularRateRadps)
        while (accelSmoothingWindow.size > imuSmoothingWindow) {
            accelSmoothingWindow.removeFirst()
            gyroSmoothingWindow.removeFirst()
        }
        val n = accelSmoothingWindow.size.toDouble()
        return sample.copy(
            accelerationMps2 = accelSmoothingWindow.fold(Vec3.ZERO) { sum, value -> sum + value } / n,
            angularRateRadps = gyroSmoothingWindow.fold(Vec3.ZERO) { sum, value -> sum + value } / n,
        )
    }

    companion object {
        /** Attitude-error process-noise amplitude in rad/sqrt(s); squared to form Q. */
        const val ATTITUDE_PROCESS_NOISE_RAD_PER_SQRT_SECOND = 0.004
        /** Accelerometer bias random walk, (m/s²)/sqrt(s). Squared to form Q. */
        const val ACCEL_BIAS_PROCESS_NOISE_MPS2_PER_SQRT_SECOND = 0.002
        /** Gyroscope bias random walk, (rad/s)/sqrt(s). Squared to form Q. */
        const val GYRO_BIAS_PROCESS_NOISE_RADPS_PER_SQRT_SECOND = 0.0002

        /** Settling window after initialization during which slow bias adaptation may run. */
        private val BIAS_CALIBRATION_WINDOW_NS = 180L * 1_000_000_000L

        /** Slow bias adaptation scale used when only the long-window qualification passes. */
        const val ADAPTATION_ACCEL_BIAS_GAIN_SCALE = 0.4
        /** Capped gyro-bias adaptation scale; protects heading from sustained cornering rates. */
        const val ADAPTATION_GYRO_BIAS_GAIN_SCALE = 1.0


        /** Default causal IMU smoothing window for mechanization (samples). */
        const val IMU_SMOOTHING_WINDOW_SAMPLES = 1

        /** Transfer-alignment promotion thresholds and priors. */
        const val ALIGNMENT_READY_ATTITUDE_STD_DEG = 3.0
        const val ALIGNMENT_READY_VELOCITY_STD_MPS = 0.35
        const val ALIGNMENT_MASTER_YAW_PRIOR_DEG = 30.0
        private val ALIGNMENT_MIN_DURATION_NANOS = 10L * 1_000_000_000L
        private const val ALIGNMENT_MIN_ACCEPTED_UPDATES = 50
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
        private val NIS_999 = doubleArrayOf(Double.NaN, 10.8276, 13.8155, 16.2662)
        private const val REACQUISITION_FIX_COUNT = 3
        private const val MAX_GNSS_SPEED_MPS = 120.0
        private const val MAX_PLAUSIBLE_GNSS_ACCELERATION_MPS2 = 15.0
        private const val AXIS_N = 1 shl 0
        private const val AXIS_E = 1 shl 1
        private const val AXIS_D = 1 shl 2
    }
}
