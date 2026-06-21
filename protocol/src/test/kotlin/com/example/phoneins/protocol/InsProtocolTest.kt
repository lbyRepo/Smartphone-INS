package com.example.phoneins.protocol

import com.example.phoneins.navigation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.File

class InsProtocolTest {
    private fun solution() = NavigationSolution(
        monotonicNanos = 123_456_789,
        unixMillis = 1_700_000_000_123,
        latitudeDeg = 1.3521,
        longitudeDeg = 103.8198,
        altitudeEllipsoidM = 25.5,
        velocityNedMps = Vec3(4.0, 1.0, -0.2),
        eulerDeg = Vec3(1.0, 2.0, 92.0),
        positionStdNedM = Vec3(2.0, 2.0, 4.0),
        velocityStdNedMps = Vec3(0.2, 0.2, 0.5),
        attitudeStdDeg = Vec3(1.0, 1.0, 3.0),
        latestImu = TimedImuSample(123_450_000, Vec3(0.1, 0.2, -9.7), Vec3(0.01, 0.02, 0.03)),
        latestGnss = GnssFix(123_000_000, 1_700_000_000_000, 1.3521, 103.8198, 25.0, Vec3(4.0, 1.0, -0.2), 2.0, 4.0, 0.2, 5.0, 18, hasDerivedDownVelocity = true),
        initializationSource = InitSource.AUTOMATIC,
        motionProfile = MotionProfile.VEHICLE,
        fixStatus = FixStatus.FIX_3D,
        qualityFlags = QualityFlag.INITIALIZED or QualityFlag.GNSS_FRESH,
        sequence = 42,
    )

    @Test
    fun frameHasFixedSizeAndRoundTrips() {
        val bytes = InsProtocol.encode(solution())
        assertEquals(224, bytes.size)
        val decoded = InsProtocol.decode(bytes)
        assertEquals(42, decoded.sequence)
        assertEquals(18, decoded.satellitesUsed)
        assertTrue(kotlin.math.abs(decoded.latitudeDeg - 1.3521) < 1e-9)
    }

    @Test
    fun corruptedFrameIsRejected() {
        val bytes = InsProtocol.encode(solution())
        bytes[80] = (bytes[80].toInt() xor 0x40).toByte()
        assertFailsWith<IllegalArgumentException> { InsProtocol.decode(bytes) }
    }

    @Test
    fun versionOneGoldenFrameMatchesNormativeCodec() {
        val golden = checkNotNull(javaClass.classLoader.getResourceAsStream("golden_v1.bin")).use { it.readBytes() }
        val encoded = InsProtocol.encode(solution().copy(sequence = 0, monotonicNanos = 1_000_000_000L))
        assertTrue(golden.contentEquals(encoded), "Protocol v1 golden frame changed")
        assertEquals(0L, InsProtocol.decode(golden).sequence)
    }

    @Test
    fun thirtyMinuteHundredHertzCadenceHasExactTimestampsAndSequence() {
        val base = solution()
        var timestamp = 10_000_000_000L
        repeat(180_000) { index ->
            val frame = InsProtocol.encode(base.copy(monotonicNanos = timestamp, sequence = index.toLong()))
            assertEquals(InsProtocol.FRAME_BYTES, frame.size)
            val decoded = InsProtocol.decode(frame)
            assertEquals(index.toLong(), decoded.sequence)
            assertEquals(timestamp, decoded.monotonicNanos)
            timestamp += 10_000_000L
        }
        assertEquals(10_000_000_000L + 180_000L * 10_000_000L, timestamp)
    }

    @Test
    fun writesReferenceCaptureForPythonDecoder() {
        val output = File("build/reports/reference-100hz.insbin")
        output.parentFile.mkdirs()
        output.outputStream().use { stream ->
            repeat(100) { index ->
                stream.write(InsProtocol.encode(solution().copy(sequence = index.toLong(), monotonicNanos = 1_000_000_000L + index * 10_000_000L)))
            }
        }
        assertEquals(100L * InsProtocol.FRAME_BYTES, output.length())
    }
}
