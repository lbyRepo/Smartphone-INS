package com.example.phoneins.protocol

import com.example.phoneins.navigation.*
import java.net.Socket
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TcpInsServerTest {
    @Test
    fun loopbackCarriesHundredHertzFramesWithoutLoss() {
        val server = TcpInsServer(0)
        server.start()
        repeat(100) { if (server.boundPort == 0) Thread.sleep(10) }
        assertTrue(server.boundPort > 0)

        val expectedFrames = 1_000
        val received = mutableListOf<DecodedInsPacket>()
        val receiveTimes = LongArray(expectedFrames)
        val complete = CountDownLatch(1)
        val client = Socket("127.0.0.1", server.boundPort)
        thread(name = "ins-test-receiver") {
            client.getInputStream().use { input ->
                repeat(expectedFrames) { index ->
                    val frame = input.readExactly(InsProtocol.FRAME_BYTES)
                    synchronized(received) { received += InsProtocol.decode(frame) }
                    receiveTimes[index] = System.nanoTime()
                }
            }
            complete.countDown()
        }
        repeat(100) { if (!server.connected) Thread.sleep(10) }
        assertTrue(server.connected)

        val base = testSolution()
        val period = 10_000_000L
        var target = System.nanoTime() + 100_000_000L
        val wallStart = target
        repeat(expectedFrames) { sequence ->
            while (System.nanoTime() < target) LockSupport.parkNanos((target - System.nanoTime()).coerceAtMost(1_000_000L))
            server.offer(InsProtocol.encode(base.copy(sequence = sequence.toLong(), monotonicNanos = target)))
            target += period
        }
        assertTrue(complete.await(5, TimeUnit.SECONDS), "Receiver did not get all frames")
        val wallSeconds = (target - period - wallStart) * 1e-9
        val rate = (expectedFrames - 1) / wallSeconds
        val arrivalRate = (expectedFrames - 1) / ((receiveTimes.last() - receiveTimes.first()) * 1e-9)
        val gapsMs = receiveTimes.asList().zipWithNext { a, b -> (b - a) * 1e-6 }.sorted()
        val p99GapMs = gapsMs[(gapsMs.size * 0.99).toInt().coerceAtMost(gapsMs.lastIndex)]
        val snapshot = synchronized(received) { received.toList() }
        assertEquals(expectedFrames, snapshot.size)
        assertEquals(0L, server.dropped)
        snapshot.forEachIndexed { index, frame -> assertEquals(index.toLong(), frame.sequence) }
        assertTrue(rate in 99.9..100.1, "Scheduled rate was $rate Hz")
        assertTrue(arrivalRate in 98.0..102.0, "Receiver observed $arrivalRate Hz")
        File("build/reports/tcp-100hz.txt").apply {
            parentFile.mkdirs()
            writeText(
                "TCP loopback production transport\n" +
                    "Frames: $expectedFrames\n" +
                    "Duration: ${"%.3f".format((receiveTimes.last() - receiveTimes.first()) * 1e-9)} s\n" +
                    "Receiver rate: ${"%.3f".format(arrivalRate)} Hz\n" +
                    "P99 inter-frame gap: ${"%.3f".format(p99GapMs)} ms\n" +
                    "Sequence loss: 0\nQueue drops: ${server.dropped}\nCRC failures: 0\nResult: PASS\n",
            )
        }
        client.close()
        server.close()
    }

    private fun java.io.InputStream.readExactly(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            check(count >= 0) { "Unexpected end of stream" }
            offset += count
        }
        return result
    }

    private fun testSolution() = NavigationSolution(
        0, 1_700_000_000_000, 1.3521, 103.8198, 25.0,
        Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
        TimedImuSample(0, Vec3(0.0, 0.0, -9.81), Vec3.ZERO), null,
        InitSource.MANUAL, MotionProfile.VEHICLE, FixStatus.NO_FIX,
        QualityFlag.INITIALIZED or QualityFlag.IMU_FRESH,
    )
}
