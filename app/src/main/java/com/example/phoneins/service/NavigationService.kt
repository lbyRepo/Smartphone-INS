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
import com.example.phoneins.data.InsRepository
import com.example.phoneins.navigation.*
import com.example.phoneins.protocol.InsProtocol
import com.example.phoneins.protocol.TcpInsServer
import kotlinx.coroutines.*
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class NavigationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = NavigationEngine()
    private lateinit var tcp: TcpInsServer
    private lateinit var aoa: AoaFrameTransport
    private lateinit var recorder: SessionRecorder
    private var acquisition: AndroidAcquisition? = null
    private var simulator: DeterministicSimulator? = null
    private var simulated = false
    private var publisherJob: Job? = null
    private var autoRequestedNanos: Long? = null
    @Volatile private var latestGnss: GnssFix? = null
    @Volatile private var latestMag: MagSample? = null
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
        tcp.start(); aoa.start()
        simulated = Build.FINGERPRINT.contains("generic", ignoreCase = true) || Build.MODEL.contains("sdk", ignoreCase = true)
        InsRepository.update { it.copy(serviceRunning = true, simulated = simulated, statusMessage = "Service running · initialize to begin", sessionNames = recorder.sessions()) }
        startSource()
        startPublisher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: Intent().setAction(ACTION_START)
        when (command.action ?: ACTION_START) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_INITIALIZE_AUTO -> {
                autoRequestedNanos = SystemClock.elapsedRealtimeNanos()
                InsRepository.update { it.copy(statusMessage = "Keep phone still · collecting bias and heading") }
                tryAutomaticInitialization()
            }
            ACTION_INITIALIZE_MANUAL -> initializeManual(command)
            ACTION_RESET -> {
                engine.reset(InsRepository.state.value.solution.motionProfile)
                autoRequestedNanos = null
                InsRepository.update { it.copy(statusMessage = "Filter reset · choose initialization") }
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
                    stopSource(); engine.reset(); stationaryImu.clear(); latestGnss = null; latestMag = null; startSource()
                    InsRepository.update { it.copy(simulated = simulated, statusMessage = if (simulated) "Simulator ready · initialize to begin" else "Live sensors ready · initialize to begin") }
                }
            }
            ACTION_RECORDING -> {
                if (command.getBooleanExtra("enabled", false)) recorder.start() else recorder.stop()
                InsRepository.update { it.copy(recording = recorder.recording, sessionNames = recorder.sessions()) }
            }
            ACTION_REPLAY -> replay(command.getStringExtra("name") ?: return START_NOT_STICKY)
            ACTION_PUBLIC_DATASET -> if (BuildConfig.SIMULATOR_AVAILABLE) replayPublicDataset()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        publisherJob?.cancel()
        stopSource()
        recorder.stop()
        tcp.close(); aoa.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        InsRepository.update { it.copy(serviceRunning = false, tcpConnected = false, aoaConnected = false, recording = false, statusMessage = "Stopped") }
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
            while (stationaryImu.size > 600) stationaryImu.removeFirst()
        }
        imuCounter.incrementAndGet()
        engine.processImu(sample)
    }

    private fun acceptGnss(fix: GnssFix) {
        latestGnss = fix
        engine.processGnss(fix)
        tryAutomaticInitialization()
    }

    private fun acceptMag(sample: MagSample) {
        latestMag = sample
    }

    private fun startPublisher() {
        publisherJob = scope.launch {
            var target = SystemClock.elapsedRealtimeNanos()
            var lastRateTime = target
            var rateFrames = 0L
            var lastImuCount = 0L
            var outputRate = 0.0
            var imuRate = 0.0
            while (isActive) {
                target += OUTPUT_PERIOD_NANOS
                val now = SystemClock.elapsedRealtimeNanos()
                simulator?.tick(now)?.let { tick ->
                    acceptImu(tick.imu)
                    tick.mag?.let(::acceptMag)
                    tick.gnss?.let(::acceptGnss)
                }
                if (autoRequestedNanos != null) tryAutomaticInitialization()

                val solution = engine.snapshot(target, System.currentTimeMillis())
                val frame = InsProtocol.encode(solution)
                tcp.offer(frame); aoa.offer(frame); recorder.append(frame)
                packetCounter++; rateFrames++
                val rateNow = SystemClock.elapsedRealtimeNanos()
                if (rateNow - lastRateTime >= 1_000_000_000L) {
                    val seconds = (rateNow - lastRateTime) * 1e-9
                    outputRate = rateFrames / seconds
                    val currentImu = imuCounter.get()
                    imuRate = (currentImu - lastImuCount) / seconds
                    lastImuCount = currentImu
                    rateFrames = 0
                    lastRateTime = rateNow
                    recorder.flush()
                }
                if (packetCounter % 10L == 0L) {
                    val drops = tcp.dropped + aoa.dropped
                    InsRepository.update {
                        it.copy(
                            solution = solution,
                            latestMag = latestMag,
                            tcpConnected = tcp.connected,
                            aoaConnected = aoa.connected,
                            recording = recorder.recording,
                            outputRateHz = outputRate,
                            imuRateHz = imuRate,
                            packetsSent = packetCounter,
                            droppedPackets = drops,
                            statusMessage = solutionStatus(solution),
                        )
                    }
                    updateNotification(solutionStatus(solution))
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
        if (now - requested < 3_000_000_000L) return
        val samples = synchronized(stationaryImu) { stationaryImu.toList().takeLast(300) }
        val gnss = latestGnss ?: return updateWaiting("Waiting for a valid GNSS fix")
        val mag = latestMag ?: return updateWaiting("Waiting for magnetometer")
        if (samples.size < 200) return updateWaiting("Collecting stationary IMU samples · ${samples.size}/200")
        val meanAcceleration = samples.map { it.accelerationMps2 }.averageVec()
        val meanAngularRate = samples.map { it.angularRateRadps }.averageVec()
        val stable = abs(meanAcceleration.norm() - 9.80665) < 0.6 && meanAngularRate.norm() < 0.08
        val magneticValid = mag.fieldMicroTesla.norm() in 15.0..100.0
        if (!stable) return updateWaiting("Motion detected · keep the phone still")
        if (!magneticValid) return updateWaiting("Magnetic field is inconsistent · use manual initialization")
        val declination = GeomagneticField(
            gnss.latitudeDeg.toFloat(), gnss.longitudeDeg.toFloat(), gnss.altitudeEllipsoidM.toFloat(), gnss.unixMillis,
        ).declination.toDouble()
        engine.initializeAutomatic(gnss, meanAcceleration, meanAngularRate, mag.fieldMicroTesla, declination)
        autoRequestedNanos = null
        InsRepository.update { it.copy(statusMessage = "Initialized · magnetometer now locked out") }
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
            .onSuccess { autoRequestedNanos = null; InsRepository.update { it.copy(statusMessage = "Manual pose initialized · zero velocity") } }
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

    private fun replayPublicDataset() {
        scope.launch {
            stopSource()
            simulated = true
            engine.reset(MotionProfile.VEHICLE)
            stationaryImu.clear()
            InsRepository.update { it.copy(simulated = true, replaying = true, statusMessage = "Replaying public Pixel 4 sensor data") }
            runCatching {
                GoogleDatasetReplay(this@NavigationService).run(
                    initialize = { engine.initializeManual(it) },
                    onImu = ::acceptImu,
                    onMag = ::acceptMag,
                    onGnss = ::acceptGnss,
                )
            }.onSuccess {
                InsRepository.update { it.copy(replaying = false, statusMessage = "Public dataset replay complete · acceptance test 1.111 m max") }
            }.onFailure { error ->
                InsRepository.update { it.copy(replaying = false, statusMessage = "Dataset replay failed: ${error.message}") }
            }
        }
    }

    private fun updateWaiting(message: String) { InsRepository.update { it.copy(statusMessage = message) } }

    private fun solutionStatus(solution: NavigationSolution) = when {
        !solution.initialized -> if (autoRequestedNanos != null) InsRepository.state.value.statusMessage else "Running · awaiting initialization"
        solution.degraded -> "Dead reckoning · GNSS stale"
        solution.qualityFlags and QualityFlag.HEADING_COURSE_AIDED != 0 -> "GNSS valid · course-aided heading"
        else -> "GNSS valid · heading coasting"
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
        const val ACTION_RECORDING = "com.example.phoneins.RECORDING"
        const val ACTION_REPLAY = "com.example.phoneins.REPLAY"
        const val ACTION_PUBLIC_DATASET = "com.example.phoneins.PUBLIC_DATASET"
        private const val CHANNEL_ID = "ins_navigation"
        private const val NOTIFICATION_ID = 1001
        private const val OUTPUT_PERIOD_NANOS = 10_000_000L
    }
}
