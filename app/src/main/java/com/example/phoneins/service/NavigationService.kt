package com.example.phoneins.service

import android.app.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.GeomagneticField
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.phoneins.MainActivity
import com.example.phoneins.R
import com.example.phoneins.BuildConfig
import com.example.phoneins.data.CsvReplayPhase
import com.example.phoneins.data.CsvReplayUiState
import com.example.phoneins.data.InsRepository
import com.example.phoneins.navigation.*
import com.example.phoneins.protocol.InsProtocol
import com.example.phoneins.protocol.TcpInsServer
import kotlinx.coroutines.*
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class NavigationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine = NavigationEngine()
    private lateinit var tcp: TcpInsServer
    private lateinit var aoa: AoaFrameTransport
    private lateinit var recorder: SessionRecorder
    private lateinit var csvLogger: CsvTrialLogger
    private lateinit var trialLogCatalog: TrialLogCatalog
    private var replayCsvLogger: CsvTrialLogger? = null
    private var acquisition: AndroidAcquisition? = null
    private var simulator: DeterministicSimulator? = null
    private var simulated = false
    private var publisherJob: Job? = null
    private var csvReplayJob: Job? = null
    private var csvReplaySeekJob: Job? = null
    private lateinit var publisherDispatcher: ExecutorCoroutineDispatcher
    private val uiUpdatePending = AtomicBoolean(false)
    private var autoRequestedNanos: Long? = null
    @Volatile private var latestGnss: GnssFix? = null
    @Volatile private var latestMag: MagSample? = null
    @Volatile private var replayClockNanos: Long? = null
    @Volatile private var forcedGnssOutage = false
    @Volatile private var latestCsvReplaySolution: NavigationSolution? = null
    private var csvReplayIndex: CsvReplayIndex? = null
    private var csvReplayRowIndex = 0
    private var csvReplayFeedState = CsvReplayFeedState()
    private var csvReplayPrepared = false
    private var sourceBeforeCsvReplaySimulated = false
    private var profileBeforeCsvReplay = MotionProfile.VEHICLE
    private var transportSequence = 0L
    private val stationaryImu = ArrayDeque<TimedImuSample>()
    private val imuCounter = AtomicLong(0)
    private var packetCounter = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val foregroundType = if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIFICATION_ID, notification("Starting navigation…"), foregroundType)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneINS:navigation")
            .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
        tcp = TcpInsServer()
        aoa = AoaFrameTransport(this)
        recorder = SessionRecorder(this)
        csvLogger = CsvTrialLogger(this)
        trialLogCatalog = TrialLogCatalog(this)
        publisherDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ins-100hz-publisher").apply { priority = Thread.MAX_PRIORITY }
        }.asCoroutineDispatcher()
        tcp.start(); aoa.start()
        val csvFile = csvLogger.start()
        simulated = Build.FINGERPRINT.contains("generic", ignoreCase = true) || Build.MODEL.contains("sdk", ignoreCase = true)
        InsRepository.update {
            it.copy(
                serviceRunning = true,
                simulated = simulated,
                forcedGnssOutage = false,
                csvLoggingEnabled = csvLogger.logging,
                csvLogFileName = csvFile.name,
                csvLogPath = csvFile.absolutePath,
                statusMessage = "Service running · CSV logging enabled · initialize to begin",
                sessionNames = recorder.sessions(),
            )
        }
        startSource()
        startPublisher()
        refreshRecentTrialLogs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: Intent().setAction(ACTION_START)
        val requestedAction = command.action ?: ACTION_START
        if (csvReplayPrepared && requestedAction in CSV_REPLAY_CONFLICTING_ACTIONS) {
            InsRepository.update { it.copy(statusMessage = "Return to live sensors before changing navigation source or initialization") }
            return START_NOT_STICKY
        }
        when (requestedAction) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_INITIALIZE_AUTO -> {
                autoRequestedNanos = SystemClock.elapsedRealtimeNanos()
                InsRepository.update {
                    it.copy(
                        initializationInProgress = true,
                        initializationProgress = 0f,
                        initializationElapsedSeconds = 0.0,
                        initializationDurationSeconds = AUTO_INITIALIZATION_DURATION_NANOS * 1e-9,
                        coarseRollDeg = Double.NaN,
                        coarsePitchDeg = Double.NaN,
                        statusMessage = "Initializing · keep still for 15 s · gravity aligning roll/pitch",
                    )
                }
                tryAutomaticInitialization()
            }
            ACTION_INITIALIZE_MANUAL -> initializeManual(command)
            ACTION_RESET -> {
                forcedGnssOutage = false
                engine.reset(InsRepository.state.value.solution.motionProfile)
                autoRequestedNanos = null
                InsRepository.update {
                    it.copy(
                        initializationInProgress = false,
                        initializationProgress = 0f,
                        initializationElapsedSeconds = 0.0,
                        coarseRollDeg = Double.NaN,
                        coarsePitchDeg = Double.NaN,
                        forcedGnssOutage = false,
                        statusMessage = "Filter reset · choose initialization",
                    )
                }
            }
            ACTION_PROFILE -> {
                val profile = runCatching { MotionProfile.valueOf(command.getStringExtra("profile") ?: "VEHICLE") }.getOrDefault(MotionProfile.VEHICLE)
                engine.setMotionProfile(profile)
                InsRepository.update { it.copy(statusMessage = "${profile.name.lowercase().replaceFirstChar(Char::titlecase)} profile selected") }
            }
            ACTION_SIMULATION -> {
                val enabled = command.getBooleanExtra("enabled", false)
                if (enabled != simulated) {
                    simulated = enabled
                    forcedGnssOutage = false
                    stopSource(); engine.reset(); stationaryImu.clear(); latestGnss = null; latestMag = null; autoRequestedNanos = null; startSource()
                    InsRepository.update {
                        it.copy(
                            simulated = simulated,
                            initializationInProgress = false,
                            initializationProgress = 0f,
                            initializationElapsedSeconds = 0.0,
                            coarseRollDeg = Double.NaN,
                            coarsePitchDeg = Double.NaN,
                            forcedGnssOutage = false,
                            statusMessage = if (simulated) "Simulator ready · initialize to begin" else "Live sensors ready · initialize to begin",
                        )
                    }
                }
            }
            ACTION_FORCE_GNSS_OUTAGE -> {
                val enabled = command.getBooleanExtra("enabled", false)
                val initialized = InsRepository.state.value.solution.initialized
                if (enabled && !initialized) {
                    forcedGnssOutage = false
                    InsRepository.update {
                        it.copy(
                            forcedGnssOutage = false,
                            statusMessage = "Initialize INS before forcing a GNSS outage",
                        )
                    }
                } else {
                    forcedGnssOutage = enabled
                    InsRepository.update {
                        it.copy(
                            forcedGnssOutage = enabled,
                            statusMessage = if (enabled) {
                                "Forced GNSS outage armed · corrections stop at the next fix"
                            } else {
                                "GNSS reacquisition requested · corrections resume at the next valid fix"
                            },
                        )
                    }
                }
            }
            ACTION_RECORDING -> {
                if (command.getBooleanExtra("enabled", false)) recorder.start() else recorder.stop()
                InsRepository.update { it.copy(recording = recorder.recording, sessionNames = recorder.sessions()) }
            }
            ACTION_REPLAY -> replay(command.getStringExtra("name") ?: return START_NOT_STICKY)
            ACTION_PUBLIC_DATASET -> if (BuildConfig.SIMULATOR_AVAILABLE) replayPublicDataset()
            ACTION_CSV_REPLAY_SELECT -> selectCsvReplay(command.getStringExtra(EXTRA_LOG_ID))
            ACTION_CSV_REPLAY_IMPORT -> importCsvReplay(command.getStringExtra(EXTRA_SOURCE_URI))
            ACTION_CSV_REPLAY_PLAY -> playCsvReplay()
            ACTION_CSV_REPLAY_PAUSE -> pauseCsvReplay()
            ACTION_CSV_REPLAY_STOP -> stopCsvReplay("Replay stopped · current state held")
            ACTION_CSV_REPLAY_RESTART -> seekCsvReplay(0L)
            ACTION_CSV_REPLAY_SEEK -> seekCsvReplay(command.getLongExtra(EXTRA_POSITION_MILLIS, 0L))
            ACTION_CSV_REPLAY_SPEED -> setCsvReplaySpeed(command.getDoubleExtra(EXTRA_SPEED, 1.0))
            ACTION_CSV_REPLAY_RETURN_TO_SOURCE -> returnFromCsvReplay()
            ACTION_REFRESH_TRIAL_LOGS -> refreshRecentTrialLogs()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        stopSource()
        val serviceJob = scope.coroutineContext[Job]
        serviceJob?.cancel()
        runBlocking {
            withTimeoutOrNull(SERVICE_SHUTDOWN_TIMEOUT_MILLIS) { serviceJob?.join() }
        }
        if (::publisherDispatcher.isInitialized) publisherDispatcher.close()
        recorder.stop()
        if (::csvLogger.isInitialized) csvLogger.stop()
        replayCsvLogger?.stop()
        tcp.close(); aoa.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        forcedGnssOutage = false
        InsRepository.update {
            it.copy(
                serviceRunning = false,
                tcpConnected = false,
                aoaConnected = false,
                recording = false,
                csvLoggingEnabled = false,
                forcedGnssOutage = false,
                initializationInProgress = false,
                initializationProgress = 0f,
                statusMessage = "Stopped",
            )
        }
        super.onDestroy()
    }

    private fun startSource() {
        if (simulated) {
            simulator = DeterministicSimulator(SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
        } else {
            acquisition = AndroidAcquisition(
                context = this,
                onImu = ::acceptImu,
                onMag = ::acceptMag,
                onGnss = ::acceptGnss,
                onCapability = { message -> InsRepository.update { it.copy(statusMessage = message) } },
            ).also { it.start() }
        }
    }

    private fun stopSource() {
        acquisition?.stop(); acquisition = null; simulator = null
    }

    private fun acceptImu(sample: TimedImuSample) {
        synchronized(stationaryImu) {
            stationaryImu.addLast(sample)
            while (stationaryImu.isNotEmpty() &&
                sample.monotonicNanos - stationaryImu.first().monotonicNanos > AUTO_INITIALIZATION_SAMPLE_BUFFER_NANOS
            ) {
                stationaryImu.removeFirst()
            }
            while (stationaryImu.size > AUTO_INITIALIZATION_MAX_BUFFER_SAMPLES) stationaryImu.removeFirst()
        }
        imuCounter.incrementAndGet()
        engine.processImu(sample)
    }

    private fun acceptGnss(fix: GnssFix) {
        latestGnss = fix
        if (forcedGnssOutage) engine.observeGnssWithoutAiding(fix) else engine.processGnss(fix)
        tryAutomaticInitialization()
    }

    private fun acceptMag(sample: MagSample) {
        latestMag = sample
    }

    private fun startPublisher() {
        publisherJob = scope.launch(publisherDispatcher) {
            Process.setThreadPriority(-2)
            var target = SystemClock.elapsedRealtimeNanos()
            var lastRateTime = target
            var lastImuCount = 0L
            var outputRate = 0.0
            var imuRate = 0.0
            val outputTimes = ArrayDeque<Long>()
            while (isActive) {
                target += OUTPUT_PERIOD_NANOS
                val now = SystemClock.elapsedRealtimeNanos()
                simulator?.tick(now)?.let { tick ->
                    acceptImu(tick.imu)
                    tick.mag?.let(::acceptMag)
                    tick.gnss?.let(::acceptGnss)
                }
                if (autoRequestedNanos != null) tryAutomaticInitialization()

                val baseSolution = if (csvReplayPrepared) {
                    latestCsvReplaySolution ?: engine.snapshot(replayClockNanos ?: target, System.currentTimeMillis())
                } else {
                    engine.snapshot(replayClockNanos ?: target, System.currentTimeMillis())
                }
                val solution = baseSolution.copy(sequence = transportSequence++)
                val frame = InsProtocol.encode(solution)
                tcp.offer(frame); aoa.offer(frame); recorder.append(frame)
                if (!csvReplayPrepared) csvLogger.append(solution)
                packetCounter++
                val rateNow = SystemClock.elapsedRealtimeNanos()
                outputTimes.addLast(rateNow)
                while (outputTimes.size > 2 && rateNow - outputTimes.first() > OUTPUT_RATE_WINDOW_NANOS) {
                    outputTimes.removeFirst()
                }
                if (rateNow - lastRateTime >= 1_000_000_000L) {
                    val seconds = (rateNow - lastRateTime) * 1e-9
                    val rateDuration = (outputTimes.last() - outputTimes.first()) * 1e-9
                    outputRate = if (rateDuration > 0.0) (outputTimes.size - 1) / rateDuration else 0.0
                    val currentImu = imuCounter.get()
                    imuRate = (currentImu - lastImuCount) / seconds
                    lastImuCount = currentImu
                    lastRateTime = rateNow
                    recorder.flush()
                    if (!csvReplayPrepared) csvLogger.flush()
                }
                if (packetCounter % 10L == 0L) {
                    val drops = tcp.dropped + aoa.dropped
                    val publishedPackets = packetCounter
                    val publishedOutputRate = outputRate
                    val publishedImuRate = imuRate
                    if (uiUpdatePending.compareAndSet(false, true)) scope.launch {
                        try {
                            val status = solutionStatus(solution)
                            InsRepository.update {
                                it.copy(
                                    solution = solution,
                                    latestMag = latestMag,
                                    tcpConnected = tcp.connected,
                                    aoaConnected = aoa.connected,
                                    recording = recorder.recording,
                                    csvLoggingEnabled = if (csvReplayPrepared) replayCsvLogger?.logging == true else csvLogger.logging,
                                    csvLogFileName = if (csvReplayPrepared) replayCsvLogger?.currentFile?.name else csvLogger.currentFile?.name,
                                    csvLogPath = if (csvReplayPrepared) replayCsvLogger?.currentFile?.absolutePath else csvLogger.currentFile?.absolutePath,
                                    outputRateHz = publishedOutputRate,
                                    imuRateHz = publishedImuRate,
                                    packetsSent = publishedPackets,
                                    droppedPackets = drops,
                                    statusMessage = status,
                                )
                            }
                            if (publishedPackets % 100L == 0L) updateNotification(status)
                        } finally {
                            uiUpdatePending.set(false)
                        }
                    }
                }
                val remaining = target - SystemClock.elapsedRealtimeNanos()
                engine.markOutputOverrun(remaining < -OUTPUT_PERIOD_NANOS)
                if (remaining > 1_000_000L) delay(remaining / 1_000_000L)
                while (SystemClock.elapsedRealtimeNanos() < target) yield()
            }
        }
    }

    private fun tryAutomaticInitialization() {
        val requested = autoRequestedNanos ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val elapsedNanos = (now - requested).coerceAtLeast(0L)
        val elapsedSeconds = elapsedNanos * 1e-9
        val progress = (elapsedNanos.toDouble() / AUTO_INITIALIZATION_DURATION_NANOS).coerceIn(0.0, 1.0)
        val samples = synchronized(stationaryImu) { stationaryImu.filter { it.monotonicNanos >= requested } }
        val coarseMeanAcceleration = samples.takeIf { it.isNotEmpty() }?.map { it.accelerationMps2 }?.averageVec()
        val coarse = coarseMeanAcceleration?.let(::gravityRollPitchDeg)
        val gnss = latestGnss
        val mag = latestMag
        val readiness = when {
            samples.size < MIN_AUTO_INITIALIZATION_IMU_SAMPLES && progress >= 1.0 ->
                "waiting for IMU samples (${samples.size}/$MIN_AUTO_INITIALIZATION_IMU_SAMPLES)"
            gnss == null -> "waiting for valid GNSS"
            !gnss.hasAltitude || !gnss.altitudeEllipsoidM.isFinite() -> "waiting for valid 3D GNSS"
            !gnss.horizontalAccuracyM.isFinite() || gnss.horizontalAccuracyM > 100.0 -> "waiting for accurate GNSS"
            mag == null -> "waiting for magnetometer"
            else -> "gravity roll/pitch alignment active"
        }
        val progressMessage = automaticInitializationMessage(elapsedSeconds, readiness, coarse)
        updateInitializationProgress(progressMessage, progress, elapsedSeconds, coarse)
        if (elapsedNanos < AUTO_INITIALIZATION_DURATION_NANOS) return
        if (samples.size < MIN_AUTO_INITIALIZATION_IMU_SAMPLES) return
        if (gnss == null) return
        if (!gnss.hasAltitude || !gnss.altitudeEllipsoidM.isFinite()) return
        if (!gnss.horizontalAccuracyM.isFinite() || gnss.horizontalAccuracyM > 100.0) return
        if (mag == null) return
        val meanAcceleration = samples.map { it.accelerationMps2 }.averageVec()
        val meanAngularRate = samples.map { it.angularRateRadps }.averageVec()
        val stable = abs(meanAcceleration.norm() - STANDARD_GRAVITY) < 0.6 && meanAngularRate.norm() < 0.08
        val magneticValid = mag.fieldMicroTesla.norm() in 15.0..100.0
        if (!stable) {
            autoRequestedNanos = now
            return updateInitializationProgress(
                "Motion detected · restarting 15 s initialization · keep phone still",
                0.0,
                0.0,
                coarse,
            )
        }
        if (!magneticValid) return updateInitializationProgress(
            "Magnetic field is inconsistent · use manual initialization",
            1.0,
            elapsedSeconds,
            coarse,
        )
        val declination = GeomagneticField(
            gnss.latitudeDeg.toFloat(), gnss.longitudeDeg.toFloat(), gnss.altitudeEllipsoidM.toFloat(), gnss.unixMillis,
        ).declination.toDouble()
        engine.initializeAutomatic(gnss, meanAcceleration, meanAngularRate, mag.fieldMicroTesla, declination)
        autoRequestedNanos = null
        val completedCoarse = gravityRollPitchDeg(meanAcceleration)
        InsRepository.update {
            it.copy(
                initializationInProgress = false,
                initializationProgress = 1f,
                initializationElapsedSeconds = AUTO_INITIALIZATION_DURATION_NANOS * 1e-9,
                initializationDurationSeconds = AUTO_INITIALIZATION_DURATION_NANOS * 1e-9,
                coarseRollDeg = completedCoarse.first,
                coarsePitchDeg = completedCoarse.second,
                statusMessage = "Initialized · gravity roll/pitch aligned · magnetometer now locked out",
            )
        }
    }

    private fun initializeManual(intent: Intent) {
        val request = ManualInitialization(
            intent.getDoubleExtra("latitude", Double.NaN), intent.getDoubleExtra("longitude", Double.NaN),
            intent.getDoubleExtra("altitude", Double.NaN), intent.getDoubleExtra("roll", Double.NaN),
            intent.getDoubleExtra("pitch", Double.NaN), intent.getDoubleExtra("yaw", Double.NaN),
        )
        val samples = synchronized(stationaryImu) { stationaryImu.toList().takeLast(300) }
        val acceleration = if (samples.isEmpty()) Vec3(0.0, 0.0, -9.80665) else samples.map { it.accelerationMps2 }.averageVec()
        val angularRate = if (samples.isEmpty()) Vec3.ZERO else samples.map { it.angularRateRadps }.averageVec()
        runCatching { engine.initializeManual(request, acceleration, angularRate) }
            .onSuccess {
                autoRequestedNanos = null
                InsRepository.update {
                    it.copy(
                        initializationInProgress = false,
                        initializationProgress = 1f,
                        initializationElapsedSeconds = 0.0,
                        coarseRollDeg = Double.NaN,
                        coarsePitchDeg = Double.NaN,
                        statusMessage = "Manual pose initialized · zero velocity",
                    )
                }
            }
            .onFailure { error -> InsRepository.update { it.copy(statusMessage = error.message ?: "Invalid manual initialization") } }
    }

    private fun replay(name: String) {
        scope.launch(Dispatchers.IO) {
            InsRepository.update { it.copy(replaying = true, statusMessage = "Replaying $name") }
            recorder.frames(name, InsProtocol.FRAME_BYTES).forEach { frame ->
                tcp.offer(frame); aoa.offer(frame); delay(10)
            }
            InsRepository.update { it.copy(replaying = false, statusMessage = "Replay complete") }
        }
    }

    private fun refreshRecentTrialLogs() {
        if (!::trialLogCatalog.isInitialized) return
        scope.launch(Dispatchers.IO) {
            val active = replayCsvLogger?.currentFile ?: csvLogger.currentFile
            val logs = trialLogCatalog.recent(active)
            InsRepository.update { it.copy(recentTrialLogs = logs) }
        }
    }

    private fun selectCsvReplay(logId: String?) {
        if (logId.isNullOrBlank()) return
        if (csvReplayPrepared) {
            return InsRepository.update {
                it.copy(statusMessage = "Return to sensors before selecting another CSV replay")
            }
        }
        scope.launch(Dispatchers.IO) {
            loadCsvReplay(runCatching { trialLogCatalog.resolve(logId) })
        }
    }

    private fun importCsvReplay(sourceUri: String?) {
        if (sourceUri.isNullOrBlank()) return
        if (csvReplayPrepared) {
            return InsRepository.update {
                it.copy(statusMessage = "Return to sensors before importing another CSV replay")
            }
        }
        scope.launch(Dispatchers.IO) {
            loadCsvReplay(runCatching { trialLogCatalog.import(android.net.Uri.parse(sourceUri)) })
        }
    }

    private fun loadCsvReplay(fileResult: Result<java.io.File>) {
        InsRepository.update {
            it.copy(
                csvReplay = it.csvReplay.copy(phase = CsvReplayPhase.INDEXING, message = "Indexing CSV trial…"),
                statusMessage = "Indexing CSV trial",
            )
        }
        fileResult.mapCatching { file -> file to CsvReplayIndex.open(file) }
            .onSuccess { (file, index) ->
                csvReplayIndex = index
                csvReplayRowIndex = 0
                csvReplayFeedState = CsvReplayFeedState()
                latestCsvReplaySolution = null
                InsRepository.update {
                    it.copy(
                        csvReplay = CsvReplayUiState(
                            phase = CsvReplayPhase.READY,
                            selectedLogId = file.canonicalPath,
                            selectedLogName = file.name,
                            durationMillis = index.durationMillis,
                            rowCount = index.rowCount,
                            speed = it.csvReplay.speed,
                            message = "Ready to reprocess ${index.rowCount} rows",
                        ),
                        statusMessage = "CSV replay ready · ${file.name}",
                    )
                }
                refreshRecentTrialLogs()
            }
            .onFailure { error ->
                csvReplayIndex = null
                InsRepository.update {
                    it.copy(
                        csvReplay = it.csvReplay.copy(
                            phase = CsvReplayPhase.ERROR,
                            message = error.message ?: "Unable to load CSV trial",
                        ),
                        statusMessage = "CSV replay error · ${error.message ?: "invalid file"}",
                    )
                }
            }
    }

    private fun playCsvReplay() {
        val index = csvReplayIndex ?: return
        val phase = InsRepository.state.value.csvReplay.phase
        if (phase !in setOf(CsvReplayPhase.READY, CsvReplayPhase.PAUSED)) return
        if (!csvReplayPrepared) prepareCsvReplay(index)
        if (replayCsvLogger?.logging != true) startReplayOutput(index.file)
        InsRepository.update {
            it.copy(
                replaying = true,
                csvReplay = it.csvReplay.copy(phase = CsvReplayPhase.PLAYING, message = "Reprocessing raw IMU and GNSS"),
                statusMessage = "CSV replay playing · ${it.csvReplay.speed}×",
            )
        }
        if (csvReplayJob?.isActive != true) launchCsvReplayLoop(index)
    }

    private fun prepareCsvReplay(index: CsvReplayIndex) {
        sourceBeforeCsvReplaySimulated = simulated
        profileBeforeCsvReplay = InsRepository.state.value.solution.motionProfile
        stopSource()
        recorder.stop()
        csvLogger.stop()
        forcedGnssOutage = false
        autoRequestedNanos = null
        stationaryImu.clear()
        latestGnss = null
        latestMag = null
        replayClockNanos = index.firstMonotonicNanos
        engine = NavigationEngine(index.row(index.initializationRow).motionProfile)
        latestCsvReplaySolution = NavigationSolution.empty(index.firstMonotonicNanos, 0L)
        csvReplayRowIndex = 0
        csvReplayFeedState = CsvReplayFeedState()
        csvReplayPrepared = true
        InsRepository.update {
            it.copy(
                simulated = true,
                recording = false,
                forcedGnssOutage = false,
                trajectoryEpoch = it.trajectoryEpoch + 1,
            )
        }
    }

    private fun startReplayOutput(source: java.io.File) {
        val logger = replayCsvLogger ?: CsvTrialLogger(this).also { replayCsvLogger = it }
        val file = logger.start("phoneins_reprocessed_${source.nameWithoutExtension}")
        InsRepository.update {
            it.copy(
                csvLoggingEnabled = true,
                csvLogFileName = file.name,
                csvLogPath = file.absolutePath,
                csvReplay = it.csvReplay.copy(outputCsvName = file.name, outputCsvPath = file.absolutePath),
            )
        }
    }

    private fun launchCsvReplayLoop(index: CsvReplayIndex) {
        csvReplayJob = scope.launch(Dispatchers.IO) {
            runCatching {
                index.cursor(csvReplayRowIndex).use { cursor ->
                    var previousTime = if (csvReplayRowIndex > 0) index.row(csvReplayRowIndex - 1).monotonicNanos else null
                    var lastUiUpdate = 0L
                    while (isActive) {
                        while (isActive && InsRepository.state.value.csvReplay.phase != CsvReplayPhase.PLAYING) delay(20)
                        if (!isActive) break
                        val row = cursor.next() ?: break
                        previousTime?.let { prior ->
                            val deltaNanos = (row.monotonicNanos - prior).coerceAtLeast(0L)
                            val speed = InsRepository.state.value.csvReplay.speed.coerceAtLeast(0.1)
                            val delayMillis = (deltaNanos / speed / 1_000_000.0).toLong()
                            if (delayMillis > 0) delay(delayMillis)
                            while (isActive && InsRepository.state.value.csvReplay.phase != CsvReplayPhase.PLAYING) delay(20)
                        }
                        if (!isActive) break
                        processCsvReplayRow(engine, row, csvReplayFeedState, aidingEnabled = !forcedGnssOutage)
                        val solution = engine.snapshot(row.monotonicNanos, row.unixMillis)
                        latestCsvReplaySolution = solution
                        latestGnss = csvReplayFeedState.latestGnss
                        replayClockNanos = row.monotonicNanos
                        replayCsvLogger?.append(solution)
                        csvReplayRowIndex = cursor.index
                        previousTime = row.monotonicNanos
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (now - lastUiUpdate >= 100_000_000L || csvReplayRowIndex == index.rowCount) {
                            lastUiUpdate = now
                            updateCsvReplayProgress(index, row.monotonicNanos)
                        }
                    }
                }
            }.onSuccess {
                if (isActive && csvReplayRowIndex >= index.rowCount) completeCsvReplay("Replay complete · final state held")
            }.onFailure { error ->
                if (error !is CancellationException) failCsvReplay(error)
            }
        }
    }

    private fun updateCsvReplayProgress(index: CsvReplayIndex, monotonicNanos: Long) {
        val position = ((monotonicNanos - index.firstMonotonicNanos).coerceAtLeast(0L) / 1_000_000L)
        InsRepository.update {
            it.copy(csvReplay = it.csvReplay.copy(positionMillis = position.coerceAtMost(index.durationMillis)))
        }
    }

    private fun pauseCsvReplay() {
        if (InsRepository.state.value.csvReplay.phase != CsvReplayPhase.PLAYING) return
        replayCsvLogger?.flush()
        InsRepository.update {
            it.copy(
                replaying = false,
                csvReplay = it.csvReplay.copy(phase = CsvReplayPhase.PAUSED, message = "Replay paused"),
                statusMessage = "CSV replay paused",
            )
        }
    }

    private fun stopCsvReplay(message: String) {
        if (!csvReplayPrepared) return
        scope.launch {
            csvReplaySeekJob?.cancelAndJoin()
            csvReplaySeekJob = null
            csvReplayJob?.cancelAndJoin()
            csvReplayJob = null
            replayCsvLogger?.stop()
            forcedGnssOutage = false
            InsRepository.update {
                it.copy(
                    replaying = false,
                    forcedGnssOutage = false,
                    csvLoggingEnabled = false,
                    csvReplay = it.csvReplay.copy(phase = CsvReplayPhase.COMPLETED, message = message),
                    statusMessage = message,
                )
            }
            refreshRecentTrialLogs()
        }
    }

    private fun completeCsvReplay(message: String) {
        replayCsvLogger?.stop()
        forcedGnssOutage = false
        InsRepository.update {
            it.copy(
                replaying = false,
                forcedGnssOutage = false,
                csvLoggingEnabled = false,
                csvReplay = it.csvReplay.copy(
                    phase = CsvReplayPhase.COMPLETED,
                    positionMillis = it.csvReplay.durationMillis,
                    message = message,
                ),
                statusMessage = message,
            )
        }
        refreshRecentTrialLogs()
    }

    private fun failCsvReplay(error: Throwable) {
        replayCsvLogger?.stop()
        InsRepository.update {
            it.copy(
                replaying = false,
                csvLoggingEnabled = false,
                csvReplay = it.csvReplay.copy(phase = CsvReplayPhase.ERROR, message = error.message ?: "Replay failed"),
                statusMessage = "CSV replay failed · ${error.message ?: "unknown error"}",
            )
        }
    }

    private fun seekCsvReplay(positionMillis: Long) {
        val index = csvReplayIndex ?: return
        if (!csvReplayPrepared) prepareCsvReplay(index)
        csvReplaySeekJob?.cancel()
        csvReplaySeekJob = scope.launch(Dispatchers.IO) {
            csvReplayJob?.cancelAndJoin()
            csvReplayJob = null
            replayCsvLogger?.stop()
            replayCsvLogger = null
            forcedGnssOutage = false
            InsRepository.update {
                it.copy(
                    replaying = false,
                    forcedGnssOutage = false,
                    csvLoggingEnabled = false,
                    csvReplay = it.csvReplay.copy(phase = CsvReplayPhase.SEEKING, message = "Rebuilding filter at selected time…"),
                    statusMessage = "Seeking CSV replay",
                )
            }

            runCatching {
                val targetPosition = positionMillis.coerceIn(0L, index.durationMillis)
                val targetRow = index.findRowAt(targetPosition)
                val candidate = NavigationEngine(index.row(index.initializationRow).motionProfile)
                val feed = CsvReplayFeedState()
                var finalRow: CsvReplayRow? = null
                if (targetPosition > 0L) {
                    index.cursor(0).use { cursor ->
                        while (cursor.index <= targetRow) {
                            val row = cursor.next() ?: break
                            processCsvReplayRow(candidate, row, feed, aidingEnabled = true)
                            finalRow = row
                        }
                    }
                }
                val snapshot = finalRow?.let { candidate.snapshot(it.monotonicNanos, it.unixMillis) }
                    ?: NavigationSolution.empty(index.firstMonotonicNanos, index.row(0).unixMillis)
                engine = candidate
                csvReplayFeedState = feed
                csvReplayRowIndex = if (targetPosition > 0L) targetRow + 1 else 0
                latestCsvReplaySolution = snapshot
                latestGnss = feed.latestGnss
                replayClockNanos = snapshot.monotonicNanos
                targetPosition
            }.onSuccess { targetPosition ->
                InsRepository.update {
                    it.copy(
                        trajectoryEpoch = it.trajectoryEpoch + 1,
                        csvReplay = it.csvReplay.copy(
                            phase = CsvReplayPhase.PAUSED,
                            positionMillis = targetPosition,
                            message = "Ready at ${formatReplayTime(targetPosition)}",
                        ),
                        statusMessage = "CSV replay paused after seek",
                    )
                }
            }.onFailure { error -> if (error !is CancellationException) failCsvReplay(error) }
        }
    }

    private fun setCsvReplaySpeed(speed: Double) {
        if (speed !in CSV_REPLAY_SPEEDS) return
        InsRepository.update {
            it.copy(
                csvReplay = it.csvReplay.copy(speed = speed),
                statusMessage = if (it.csvReplay.phase == CsvReplayPhase.PLAYING) "CSV replay playing · ${speed}×" else it.statusMessage,
            )
        }
    }

    private fun returnFromCsvReplay() {
        scope.launch {
            csvReplaySeekJob?.cancelAndJoin()
            csvReplaySeekJob = null
            csvReplayJob?.cancelAndJoin()
            csvReplayJob = null
            replayCsvLogger?.stop()
            replayCsvLogger = null
            if (csvReplayPrepared) {
                engine = NavigationEngine(profileBeforeCsvReplay)
                latestCsvReplaySolution = null
                replayClockNanos = null
                latestGnss = null
                latestMag = null
                forcedGnssOutage = false
                csvReplayPrepared = false
                simulated = sourceBeforeCsvReplaySimulated
                startSource()
                val file = csvLogger.start()
                InsRepository.update {
                    it.copy(
                        simulated = simulated,
                        replaying = false,
                        forcedGnssOutage = false,
                        solution = NavigationSolution.empty().copy(motionProfile = profileBeforeCsvReplay),
                        csvLoggingEnabled = true,
                        csvLogFileName = file.name,
                        csvLogPath = file.absolutePath,
                        csvReplay = CsvReplayUiState(speed = it.csvReplay.speed),
                        trajectoryEpoch = it.trajectoryEpoch + 1,
                        statusMessage = if (simulated) "Simulator restored · initialize to begin" else "Live sensors restored · initialize to begin",
                    )
                }
            } else {
                csvReplayIndex = null
                InsRepository.update { it.copy(csvReplay = CsvReplayUiState(speed = it.csvReplay.speed)) }
            }
            refreshRecentTrialLogs()
        }
    }

    private fun processCsvReplayRow(
        targetEngine: NavigationEngine,
        row: CsvReplayRow,
        feed: CsvReplayFeedState,
        aidingEnabled: Boolean,
    ) {
        var imu = row.takeIf {
            it.imuMonotonicNanos > 0L && it.imuMonotonicNanos != feed.lastImuNanos &&
                it.acceleration.isFiniteVec() && it.angularRate.isFiniteVec()
        }?.let { TimedImuSample(it.imuMonotonicNanos, it.acceleration, it.angularRate) }
        val gnss = row.takeIf {
            it.gnssMonotonicNanos > 0L && it.gnssMonotonicNanos != feed.lastGnssNanos &&
                it.gnssPosition.x.isFinite() && it.gnssPosition.y.isFinite()
        }?.let {
            GnssFix(
                monotonicNanos = it.gnssMonotonicNanos,
                unixMillis = it.gnssUnixMillis,
                latitudeDeg = it.gnssPosition.x,
                longitudeDeg = it.gnssPosition.y,
                altitudeEllipsoidM = it.gnssPosition.z,
                velocityNedMps = it.gnssVelocityNed,
                horizontalAccuracyM = it.gnssHorizontalAccuracy,
                verticalAccuracyM = it.gnssVerticalAccuracy,
                speedAccuracyMps = it.gnssSpeedAccuracy,
                bearingAccuracyDeg = it.gnssBearingAccuracy,
                satellitesUsed = it.gnssSatellites,
                hasAltitude = it.gnssHasAltitude,
                hasHorizontalVelocity = it.gnssHasHorizontalVelocity,
                hasDerivedDownVelocity = it.gnssHasDerivedDownVelocity,
                isMock = it.gnssIsMock,
            )
        }

        if (!feed.initialized && row.initialized) {
            targetEngine.initializeReplay(
                ReplayInitialization(
                    latitudeDeg = row.insPosition.x,
                    longitudeDeg = row.insPosition.y,
                    altitudeEllipsoidM = row.insPosition.z,
                    velocityNedMps = row.insVelocityNed,
                    eulerDeg = row.insEulerDeg,
                    accelerometerBiasMps2 = row.accelerometerBias,
                    gyroBiasRadps = row.gyroBias,
                    motionProfile = row.motionProfile,
                    imuSample = imu ?: feed.latestImu,
                ),
            )
            feed.initialized = true
            imu?.let { feed.lastImuNanos = it.monotonicNanos }
            imu = null
        }

        fun applyImu(sample: TimedImuSample) {
            targetEngine.processImu(sample)
            feed.lastImuNanos = sample.monotonicNanos
            feed.latestImu = sample
        }
        fun applyGnss(fix: GnssFix) {
            if (aidingEnabled) targetEngine.processGnss(fix) else targetEngine.observeGnssWithoutAiding(fix)
            feed.lastGnssNanos = fix.monotonicNanos
            feed.latestGnss = fix
        }
        when {
            imu != null && gnss != null && imu.monotonicNanos <= gnss.monotonicNanos -> { applyImu(imu); applyGnss(gnss) }
            imu != null && gnss != null -> { applyGnss(gnss); applyImu(imu) }
            imu != null -> applyImu(imu)
            gnss != null -> applyGnss(gnss)
        }
    }

    private fun Vec3.isFiniteVec() = x.isFinite() && y.isFinite() && z.isFinite()

    private fun formatReplayTime(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun replayPublicDataset() {
        scope.launch {
            stopSource()
            simulated = true
            forcedGnssOutage = false
            engine.reset(MotionProfile.VEHICLE)
            stationaryImu.clear()
            replayClockNanos = null
            autoRequestedNanos = null
            InsRepository.update {
                it.copy(
                    simulated = true,
                    replaying = true,
                    initializationInProgress = false,
                    initializationProgress = 0f,
                    initializationElapsedSeconds = 0.0,
                    coarseRollDeg = Double.NaN,
                    coarsePitchDeg = Double.NaN,
                    forcedGnssOutage = false,
                    statusMessage = "Replaying public Pixel 4 sensor data",
                )
            }
            runCatching {
                GoogleDatasetReplay(this@NavigationService).run(
                    initialize = { engine.initializeManual(it) },
                    onImu = { sample -> replayClockNanos = sample.monotonicNanos; acceptImu(sample) },
                    onMag = ::acceptMag,
                    onGnss = { fix ->
                        replayClockNanos = fix.monotonicNanos
                        acceptGnss(fix)
                    },
                )
            }.onSuccess {
                if (forcedGnssOutage) latestGnss?.let(engine::processGnss)
                forcedGnssOutage = false
                InsRepository.update {
                    it.copy(
                        replaying = false,
                        forcedGnssOutage = false,
                        statusMessage = "Pixel 4 route complete",
                    )
                }
            }.onFailure { error ->
                forcedGnssOutage = false
                InsRepository.update {
                    it.copy(
                        replaying = false,
                        forcedGnssOutage = false,
                        statusMessage = "Dataset replay failed: ${error.message}",
                    )
                }
            }
        }
    }

    private fun updateInitializationProgress(
        message: String,
        progress: Double,
        elapsedSeconds: Double,
        coarseRollPitchDeg: Pair<Double, Double>?,
    ) {
        InsRepository.update {
            it.copy(
                initializationInProgress = autoRequestedNanos != null,
                initializationProgress = progress.toFloat(),
                initializationElapsedSeconds = elapsedSeconds.coerceAtLeast(0.0),
                initializationDurationSeconds = AUTO_INITIALIZATION_DURATION_NANOS * 1e-9,
                coarseRollDeg = coarseRollPitchDeg?.first ?: Double.NaN,
                coarsePitchDeg = coarseRollPitchDeg?.second ?: Double.NaN,
                statusMessage = message,
            )
        }
    }

    private fun automaticInitializationMessage(
        elapsedSeconds: Double,
        readiness: String,
        coarseRollPitchDeg: Pair<Double, Double>?,
    ): String {
        val elapsed = elapsedSeconds.coerceIn(0.0, AUTO_INITIALIZATION_DURATION_NANOS * 1e-9)
        val coarse = coarseRollPitchDeg?.let {
            " · coarse R/P ${String.format(Locale.US, "%.1f", it.first)}°/${String.format(Locale.US, "%.1f", it.second)}°"
        }.orEmpty()
        return "Initializing · ${String.format(Locale.US, "%.1f", elapsed)}/15.0 s · keep still · $readiness$coarse"
    }

    private fun gravityRollPitchDeg(acceleration: Vec3): Pair<Double, Double> {
        val g = acceleration.norm().coerceAtLeast(1e-6)
        val pitch = kotlin.math.asin((acceleration.x / g).coerceIn(-1.0, 1.0))
        val roll = kotlin.math.atan2(-acceleration.y, -acceleration.z)
        return Math.toDegrees(roll) to Math.toDegrees(pitch)
    }

    private fun solutionStatus(solution: NavigationSolution) = when {
        csvReplayPrepared && InsRepository.state.value.csvReplay.phase == CsvReplayPhase.PLAYING ->
            "CSV replay · ${InsRepository.state.value.csvReplay.speed}× · reprocessing raw sensors"
        csvReplayPrepared && InsRepository.state.value.csvReplay.phase == CsvReplayPhase.PAUSED -> "CSV replay paused"
        csvReplayPrepared && InsRepository.state.value.csvReplay.phase == CsvReplayPhase.SEEKING -> "CSV replay seeking"
        csvReplayPrepared && InsRepository.state.value.csvReplay.phase == CsvReplayPhase.COMPLETED -> "Replay complete · final state held"
        !solution.initialized -> if (autoRequestedNanos != null) InsRepository.state.value.statusMessage else "Running · awaiting initialization"
        forcedGnssOutage && solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0 ->
            "Forced GNSS outage · raw fixes visible · dead reckoning"
        forcedGnssOutage -> "Forced GNSS outage armed · waiting for next GNSS fix"
        solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0 -> "GNSS visible · aiding paused · dead reckoning"
        solution.degraded -> "Dead reckoning · GNSS stale"
        solution.qualityFlags and QualityFlag.GNSS_UPDATE_REJECTED != 0 -> "GNSS fix present · correction rejected"
        solution.qualityFlags and QualityFlag.STATIONARY_CONSTRAINT != 0 -> "GNSS valid · stationary hold"
        solution.qualityFlags and QualityFlag.HEADING_COURSE_AIDED != 0 -> "GNSS valid · course-aided heading"
        solution.qualityFlags and QualityFlag.GNSS_POSITION_VALID != 0 -> "GNSS valid · position aided · heading coasting"
        else -> "GNSS fix present · awaiting correction"
    }

    private fun List<Vec3>.averageVec(): Vec3 {
        if (isEmpty()) return Vec3.ZERO
        return fold(Vec3.ZERO) { total, value -> total + value } / size.toDouble()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.service_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ins).setContentTitle("Smartphone INS").setContentText(text)
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }

    companion object {
        const val ACTION_START = "com.example.phoneins.START"
        const val ACTION_STOP = "com.example.phoneins.STOP"
        const val ACTION_INITIALIZE_AUTO = "com.example.phoneins.INIT_AUTO"
        const val ACTION_INITIALIZE_MANUAL = "com.example.phoneins.INIT_MANUAL"
        const val ACTION_RESET = "com.example.phoneins.RESET"
        const val ACTION_PROFILE = "com.example.phoneins.PROFILE"
        const val ACTION_SIMULATION = "com.example.phoneins.SIMULATION"
        const val ACTION_FORCE_GNSS_OUTAGE = "com.example.phoneins.FORCE_GNSS_OUTAGE"
        const val ACTION_RECORDING = "com.example.phoneins.RECORDING"
        const val ACTION_REPLAY = "com.example.phoneins.REPLAY"
        const val ACTION_PUBLIC_DATASET = "com.example.phoneins.PUBLIC_DATASET"
        const val ACTION_CSV_REPLAY_SELECT = "com.example.phoneins.CSV_REPLAY_SELECT"
        const val ACTION_CSV_REPLAY_IMPORT = "com.example.phoneins.CSV_REPLAY_IMPORT"
        const val ACTION_CSV_REPLAY_PLAY = "com.example.phoneins.CSV_REPLAY_PLAY"
        const val ACTION_CSV_REPLAY_PAUSE = "com.example.phoneins.CSV_REPLAY_PAUSE"
        const val ACTION_CSV_REPLAY_STOP = "com.example.phoneins.CSV_REPLAY_STOP"
        const val ACTION_CSV_REPLAY_RESTART = "com.example.phoneins.CSV_REPLAY_RESTART"
        const val ACTION_CSV_REPLAY_SEEK = "com.example.phoneins.CSV_REPLAY_SEEK"
        const val ACTION_CSV_REPLAY_SPEED = "com.example.phoneins.CSV_REPLAY_SPEED"
        const val ACTION_CSV_REPLAY_RETURN_TO_SOURCE = "com.example.phoneins.CSV_REPLAY_RETURN_TO_SOURCE"
        const val ACTION_REFRESH_TRIAL_LOGS = "com.example.phoneins.REFRESH_TRIAL_LOGS"
        const val EXTRA_LOG_ID = "logId"
        const val EXTRA_SOURCE_URI = "sourceUri"
        const val EXTRA_POSITION_MILLIS = "positionMillis"
        const val EXTRA_SPEED = "speed"
        private const val CHANNEL_ID = "ins_navigation"
        private const val NOTIFICATION_ID = 1001
        private const val SERVICE_SHUTDOWN_TIMEOUT_MILLIS = 2_000L
        private const val OUTPUT_PERIOD_NANOS = 10_000_000L
        private const val OUTPUT_RATE_WINDOW_NANOS = 5_000_000_000L
        private const val STANDARD_GRAVITY = 9.80665
        private const val AUTO_INITIALIZATION_DURATION_NANOS = 15_000_000_000L
        private const val AUTO_INITIALIZATION_SAMPLE_BUFFER_NANOS = 20_000_000_000L
        private const val AUTO_INITIALIZATION_MAX_BUFFER_SAMPLES = 5_000
        private const val MIN_AUTO_INITIALIZATION_IMU_SAMPLES = 300
        private val CSV_REPLAY_SPEEDS = setOf(0.5, 1.0, 2.0, 10.0)
        private val CSV_REPLAY_CONFLICTING_ACTIONS = setOf(
            ACTION_INITIALIZE_AUTO,
            ACTION_INITIALIZE_MANUAL,
            ACTION_RESET,
            ACTION_PROFILE,
            ACTION_SIMULATION,
            ACTION_RECORDING,
            ACTION_REPLAY,
            ACTION_PUBLIC_DATASET,
        )
    }
}

private data class CsvReplayFeedState(
    var lastImuNanos: Long = Long.MIN_VALUE,
    var lastGnssNanos: Long = Long.MIN_VALUE,
    var initialized: Boolean = false,
    var latestImu: TimedImuSample? = null,
    var latestGnss: GnssFix? = null,
)
