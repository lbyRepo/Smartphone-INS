package com.example.phoneins

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.phoneins.data.InsRepository
import com.example.phoneins.protocol.InsProtocol
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

    private fun startService(action: String) {
        ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java).setAction(action))
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
