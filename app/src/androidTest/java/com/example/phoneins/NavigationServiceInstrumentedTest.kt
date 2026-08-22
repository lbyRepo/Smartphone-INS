package com.example.phoneins

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.phoneins.data.InsRepository
import com.example.phoneins.data.CsvReplayPhase
import com.example.phoneins.protocol.InsProtocol
import com.example.phoneins.navigation.InitSource
import com.example.phoneins.navigation.QualityFlag
import com.example.phoneins.service.CsvTrialLogger
import com.example.phoneins.service.NavigationService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.net.Socket
import java.io.File
import java.util.concurrent.TimeUnit

/** End-to-end checks that require a real Android runtime, not a host Compose renderer. */
@RunWith(AndroidJUnit4::class)
class NavigationServiceInstrumentedTest {
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanStart() {
        context.stopService(Intent(context, NavigationService::class.java))
        InsRepository.reset()
        Thread.sleep(250)
    }

    @After
    fun stopService() {
        context.stopService(Intent(context, NavigationService::class.java))
        await(5_000) { !InsRepository.state.value.serviceRunning }
        InsRepository.reset()
    }

    @Test
    fun publicPhoneDatasetReachesProductionUiStateWithoutCrash() {
        startService(NavigationService.ACTION_START)
        await(10_000) { InsRepository.state.value.serviceRunning }

        startService(NavigationService.ACTION_PUBLIC_DATASET)
        await(10_000) { InsRepository.state.value.replaying }
        await(60_000) { !InsRepository.state.value.replaying }

        val state = InsRepository.state.value
        assertTrue("Navigation solution was not initialized", state.solution.initialized)
        assertNotNull("Raw IMU did not reach UI state", state.solution.latestImu)
        assertNotNull("Raw magnetometer did not reach UI state", state.latestMag)
        assertNotNull("Raw GNSS did not reach UI state", state.solution.latestGnss)
        assertTrue("Service stopped during replay", state.serviceRunning)
        assertTrue("Replay reported a failure: ${state.statusMessage}", !state.statusMessage.contains("failed", ignoreCase = true))
    }

    @Test
    fun publicDatasetGnssAidingCanBeSuspendedAndReacquiredAtRuntime() {
        startService(NavigationService.ACTION_START)
        await(10_000) { InsRepository.state.value.serviceRunning }
        startService(NavigationService.ACTION_PUBLIC_DATASET)
        await(10_000) {
            val state = InsRepository.state.value
            state.replaying && state.solution.initialized && state.solution.latestGnss != null
        }

        val beforeOutage = checkNotNull(InsRepository.state.value.solution.latestGnss).monotonicNanos
        setForcedGnssOutage(true)
        await(10_000) {
            val state = InsRepository.state.value
            state.forcedGnssOutage &&
                state.solution.latestGnss?.monotonicNanos?.let { it > beforeOutage } == true &&
                state.solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0
        }
        val duringOutage = checkNotNull(InsRepository.state.value.solution.latestGnss).monotonicNanos
        await(10_000) {
            InsRepository.state.value.solution.latestGnss?.monotonicNanos?.let { it > duringOutage } == true
        }
        assertTrue("Raw GNSS stopped while corrections were withheld", InsRepository.state.value.forcedGnssOutage)

        setForcedGnssOutage(false)
        await(10_000) {
            val state = InsRepository.state.value
            !state.forcedGnssOutage &&
                state.solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED == 0
        }
        assertTrue("Replay ended before runtime reacquisition was exercised", InsRepository.state.value.replaying)
    }

    @Test
    fun simulatedAcquisitionUsesTheSameRuntimeGnssOutageGate() {
        startService(NavigationService.ACTION_START)
        await(10_000) { InsRepository.state.value.serviceRunning }
        startService(NavigationService.ACTION_SIMULATION) { putExtra("enabled", true) }
        await(10_000) { InsRepository.state.value.simulated }
        startService(NavigationService.ACTION_INITIALIZE_MANUAL) {
            putExtra("latitude", 1.3521)
            putExtra("longitude", 103.8198)
            putExtra("altitude", 25.0)
            putExtra("roll", 0.0)
            putExtra("pitch", 0.0)
            putExtra("yaw", 0.0)
        }
        await(10_000) {
            InsRepository.state.value.solution.initialized && InsRepository.state.value.solution.latestGnss != null
        }

        val beforeOutage = checkNotNull(InsRepository.state.value.solution.latestGnss).monotonicNanos
        setForcedGnssOutage(true)
        await(10_000) {
            val state = InsRepository.state.value
            state.forcedGnssOutage &&
                state.solution.latestGnss?.monotonicNanos?.let { it > beforeOutage } == true &&
                state.solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0
        }

        setForcedGnssOutage(false)
        await(10_000) {
            val state = InsRepository.state.value
            !state.forcedGnssOutage &&
                state.solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED == 0
        }
        assertTrue("Service stopped during simulated reacquisition", InsRepository.state.value.serviceRunning)
    }

    @Test
    fun foregroundServiceTcpStreamSustainsHundredHertzWithoutLoss() {
        startService(NavigationService.ACTION_START)
        await(10_000) { InsRepository.state.value.serviceRunning }
        val socket = connectWithRetry()
        socket.soTimeout = 5_000

        val frames = 500
        val receiveTimes = LongArray(frames)
        val sequences = LongArray(frames)
        socket.getInputStream().use { input ->
            repeat(frames) { index ->
                val decoded = InsProtocol.decode(input.readExactly(InsProtocol.FRAME_BYTES))
                receiveTimes[index] = System.nanoTime()
                sequences[index] = decoded.sequence
            }
        }
        socket.close()

        sequences.asList().zipWithNext().forEach { (previous, next) ->
            assertEquals("Sequence discontinuity", (previous + 1) and 0xffff_ffffL, next)
        }
        val durationSeconds = (receiveTimes.last() - receiveTimes.first()) * 1e-9
        val observedRate = (frames - 1) / durationSeconds
        val gapsMs = receiveTimes.asList().zipWithNext { a, b -> (b - a) * 1e-6 }.sorted()
        val p99GapMs = gapsMs[(gapsMs.size * 0.99).toInt().coerceAtMost(gapsMs.lastIndex)]
        val state = InsRepository.state.value
        println("Android service TCP: $frames frames, ${"%.3f".format(observedRate)} Hz, ${"%.3f".format(p99GapMs)} ms p99, ${state.droppedPackets} drops")
        assertTrue("Observed TCP rate was $observedRate Hz", observedRate in 98.0..102.0)
        assertTrue("Publisher-reported rate was ${state.outputRateHz} Hz", state.outputRateHz in 98.0..102.0)
        assertEquals("Transport queue dropped frames", 0L, state.droppedPackets)
        assertTrue("Service stopped while streaming", state.serviceRunning)
    }

    @Test
    fun csvTrialIsReprocessedSavedAndHeldWithoutModifyingSource() {
        val source = writeReplayCsv("instrumented_source.csv", 101)
        val originalBytes = source.readBytes()
        startService(NavigationService.ACTION_START)
        await(10_000) { InsRepository.state.value.serviceRunning }

        startService(NavigationService.ACTION_CSV_REPLAY_SELECT) {
            putExtra(NavigationService.EXTRA_LOG_ID, source.canonicalPath)
        }
        await(10_000) { InsRepository.state.value.csvReplay.phase == CsvReplayPhase.READY }
        startService(NavigationService.ACTION_CSV_REPLAY_SPEED) { putExtra(NavigationService.EXTRA_SPEED, 10.0) }
        startService(NavigationService.ACTION_CSV_REPLAY_PLAY)
        await(15_000) { InsRepository.state.value.csvReplay.phase == CsvReplayPhase.COMPLETED }

        val state = InsRepository.state.value
        assertEquals(InitSource.REPLAY, state.solution.initializationSource)
        assertNotNull("Reprocessed raw IMU missing", state.solution.latestImu)
        assertNotNull("Reprocessed raw GNSS missing", state.solution.latestGnss)
        assertTrue("Final replay state was not held", state.solution.initialized)
        assertTrue("Original CSV was modified", source.readBytes().contentEquals(originalBytes))
        val output = File(checkNotNull(state.csvReplay.outputCsvPath))
        assertTrue("Reprocessed CSV was not created", output.isFile)
        assertEquals("Reprocessed output must contain one row per source epoch", 102, output.useLines { it.count() })
        assertEquals(CsvTrialLogger.HEADER, output.bufferedReader().use { it.readLine() })

        startService(NavigationService.ACTION_CSV_REPLAY_RETURN_TO_SOURCE)
        await(10_000) { InsRepository.state.value.csvReplay.phase == CsvReplayPhase.IDLE }
        assertTrue("CSV logging did not resume", InsRepository.state.value.csvLoggingEnabled)
    }

    @Test
    fun csvReplayCanPauseAndSeekWithoutPublishingPartialFilterState() {
        val source = writeReplayCsv("instrumented_seek_source.csv", 401)
        startService(NavigationService.ACTION_START)
        await(10_000) { InsRepository.state.value.serviceRunning }
        startService(NavigationService.ACTION_CSV_REPLAY_SELECT) {
            putExtra(NavigationService.EXTRA_LOG_ID, source.canonicalPath)
        }
        await(10_000) { InsRepository.state.value.csvReplay.phase == CsvReplayPhase.READY }
        startService(NavigationService.ACTION_CSV_REPLAY_PLAY)
        await(10_000) { InsRepository.state.value.csvReplay.positionMillis >= 100L }
        setForcedGnssOutage(true)
        await(10_000) {
            val state = InsRepository.state.value
            state.forcedGnssOutage && state.solution.qualityFlags and QualityFlag.GNSS_AIDING_SUSPENDED != 0
        }
        startService(NavigationService.ACTION_CSV_REPLAY_PAUSE)
        await(5_000) { InsRepository.state.value.csvReplay.phase == CsvReplayPhase.PAUSED }
        val stableSequence = InsRepository.state.value.solution.sequence

        startService(NavigationService.ACTION_CSV_REPLAY_SEEK) {
            putExtra(NavigationService.EXTRA_POSITION_MILLIS, 2_000L)
        }
        await(10_000) {
            val replay = InsRepository.state.value.csvReplay
            replay.phase == CsvReplayPhase.PAUSED && replay.positionMillis == 2_000L
        }
        val state = InsRepository.state.value
        assertTrue("Transport sequence moved backwards across seek", state.solution.sequence >= stableSequence)
        assertTrue("Seek exposed an uninitialized partial filter", state.solution.initialized)
        assertTrue("Seek did not reset forced GNSS outage", !state.forcedGnssOutage)
    }

    private fun startService(action: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(context, NavigationService::class.java).setAction(action).apply(configure)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun setForcedGnssOutage(enabled: Boolean) {
        startService(NavigationService.ACTION_FORCE_GNSS_OUTAGE) { putExtra("enabled", enabled) }
    }

    private fun writeReplayCsv(name: String, rows: Int): File {
        val file = File(CsvTrialLogger.trialLogDirectory(context), name)
        val columns = CsvTrialLogger.HEADER.split(',')
        file.bufferedWriter().use { output ->
            output.appendLine(CsvTrialLogger.HEADER)
            repeat(rows) { index ->
                val time = 50_000_000_000L + index * 10_000_000L
                val values = columns.associateWith { "0" }.toMutableMap()
                values["sequence"] = index.toString()
                values["monotonic_nanos"] = time.toString()
                values["unix_millis"] = (1_750_000_000_000L + index * 10L).toString()
                values["utc_time_iso"] = "2025-06-15T00:00:00Z"
                values["quality_flags"] = QualityFlag.INITIALIZED.toString()
                values["initialization_source"] = "MANUAL"
                values["motion_profile"] = "VEHICLE"
                values["fix_status"] = "FIX_3D"
                values["initialized"] = "1"
                values["ins_latitude_deg"] = "1.3521"
                values["ins_longitude_deg"] = "103.8198"
                values["ins_altitude_m"] = "25.0"
                values["ins_roll_deg"] = "0.0"
                values["ins_pitch_deg"] = "0.0"
                values["ins_yaw_deg"] = "45.0"
                values["imu_monotonic_nanos"] = time.toString()
                values["imu_accel_z_mps2"] = "-9.80665"
                values["gnss_monotonic_nanos"] = (50_000_000_000L + (index / 20) * 200_000_000L).toString()
                values["gnss_unix_millis"] = (1_750_000_000_000L + (index / 20) * 200L).toString()
                values["gnss_latitude_deg"] = (1.3521 + index * 1e-7).toString()
                values["gnss_longitude_deg"] = (103.8198 + index * 1e-7).toString()
                values["gnss_altitude_m"] = "25.0"
                values["gnss_velocity_north_mps"] = "1.0"
                values["gnss_velocity_east_mps"] = "1.0"
                values["gnss_horizontal_accuracy_m"] = "2.0"
                values["gnss_vertical_accuracy_m"] = "4.0"
                values["gnss_speed_accuracy_mps"] = "0.2"
                values["gnss_bearing_accuracy_deg"] = "5.0"
                values["gnss_satellites_used"] = "12"
                values["gnss_has_altitude"] = "1"
                values["gnss_has_horizontal_velocity"] = "1"
                output.appendLine(columns.joinToString(",") { values.getValue(it) })
            }
        }
        return file
    }

    private fun connectWithRetry(): Socket {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var failure: Exception? = null
        while (System.nanoTime() < deadline) {
            try {
                return Socket("127.0.0.1", 29_500)
            } catch (error: Exception) {
                failure = error
                Thread.sleep(100)
            }
        }
        throw AssertionError("TCP server did not become available", failure)
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            check(count >= 0) { "Unexpected end of INS stream" }
            offset += count
        }
        return bytes
    }

    private fun await(timeoutMillis: Long, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        assertTrue("Timed out after $timeoutMillis ms", predicate())
    }
}
