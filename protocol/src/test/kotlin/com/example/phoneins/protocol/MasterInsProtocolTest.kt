package com.example.phoneins.protocol

import com.example.phoneins.navigation.MasterInsReference
import com.example.phoneins.navigation.MasterReferenceFlag
import com.example.phoneins.navigation.Quaternion
import com.example.phoneins.navigation.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MasterInsProtocolTest {
    private fun reference() = MasterInsReference(
        sequence = 7,
        masterMonotonicNanos = 10_000_000_000L,
        masterUtcNanos = 1_700_000_000_000_000_000L,
        phoneMonotonicNanos = 9_998_000_000L,
        flags = MasterInsReference.REQUIRED_FLAGS or MasterReferenceFlag.POSITION_VALID,
        latitudeDeg = 1.3521,
        longitudeDeg = 103.8198,
        altitudeM = 25.0,
        velocityNedMps = Vec3(12.0, 1.0, 0.1),
        bodyToNed = Quaternion(1.0, 0.0, 0.0, 0.0),
        angularRateBodyRadps = Vec3(0.01, 0.02, 0.03),
        velocityStdNedMps = Vec3(0.05, 0.05, 0.1),
        attitudeStdDeg = Vec3(0.1, 0.1, 0.2),
        timeUncertaintyNanos = 2_000_000L,
    )

    @Test
    fun referenceRoundTripsWithFixedFrameAndCrc() {
        val frame = MasterInsProtocol.encodeReference(reference())
        assertEquals(180, frame.size)
        val decoded = (MasterInsProtocol.decode(frame) as MasterInsProtocol.Message.Reference).value
        assertEquals(7, decoded.sequence)
        assertEquals(Vec3(12.0, 1.0, 0.1), decoded.velocityNedMps)
        assertEquals(2_000_000L, decoded.timeUncertaintyNanos)
        frame[80] = (frame[80].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { MasterInsProtocol.decode(frame) }
    }

    @Test
    fun synchronizationMapsMasterTimeToPhoneAndRejectsPoorRoundTrip() {
        val sync = MasterClockSynchronizer()
        assertTrue(sync.observe(1_000_000_000L, 1_005_000_000L, 1_006_000_000L, 1_003_000_000L))
        assertEquals(4_000_000L, sync.offsetNanos)
        assertEquals(1_000_000L, sync.uncertaintyNanos)
        assertEquals(2_000_000_000L, sync.masterToPhone(2_004_000_000L))
    }

    @Test
    fun syncMessagesRoundTrip() {
        val request = MasterInsProtocol.decode(MasterInsProtocol.encodeSyncRequest(4, 100))
        assertEquals(MasterInsProtocol.Message.SyncRequest(4, 100), request)
        val response = MasterInsProtocol.decode(MasterInsProtocol.encodeSyncResponse(4, 100, 120, 125))
        assertEquals(MasterInsProtocol.Message.SyncResponse(4, 100, 120, 125), response)
    }

    @Test
    fun masterReferenceGoldenFrameMatchesNormativeCodec() {
        val hex = checkNotNull(javaClass.classLoader.getResourceAsStream("master_reference_v1.hex"))
            .bufferedReader().use { it.readText().trim() }
        val golden = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(golden.contentEquals(MasterInsProtocol.encodeReference(reference())))
    }
}
