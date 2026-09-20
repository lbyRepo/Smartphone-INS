package com.example.phoneins.protocol

import com.example.phoneins.navigation.MasterInsReference
import com.example.phoneins.navigation.Quaternion
import com.example.phoneins.navigation.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Separate, bidirectional master-reference protocol. It does not alter INS1 frames. */
object MasterInsProtocol {
    const val VERSION: Byte = 1
    const val TYPE_REFERENCE: Byte = 1
    const val TYPE_SYNC_REQUEST: Byte = 2
    const val TYPE_SYNC_RESPONSE: Byte = 3
    const val HEADER_BYTES = 32
    const val REFERENCE_PAYLOAD_BYTES = 144
    const val REFERENCE_BYTES = HEADER_BYTES + REFERENCE_PAYLOAD_BYTES + 4
    const val SYNC_REQUEST_PAYLOAD_BYTES = 8
    const val SYNC_RESPONSE_PAYLOAD_BYTES = 24
    val MAGIC = byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())

    data class Header(
        val type: Byte,
        val payloadBytes: Int,
        val sequence: Long,
        val monotonicNanos: Long,
        val utcNanos: Long,
    )

    sealed interface Message {
        data class Reference(val value: MasterInsReference) : Message
        data class SyncRequest(val sequence: Long, val phoneSendNanos: Long) : Message
        data class SyncResponse(
            val sequence: Long,
            val phoneSendNanos: Long,
            val masterReceiveNanos: Long,
            val masterSendNanos: Long,
        ) : Message
    }

    fun encodeReference(reference: MasterInsReference): ByteArray {
        val b = allocate(TYPE_REFERENCE, REFERENCE_PAYLOAD_BYTES, reference.sequence, reference.masterMonotonicNanos, reference.masterUtcNanos)
        b.putInt(reference.flags).putInt(0)
        b.putDouble(reference.latitudeDeg).putDouble(reference.longitudeDeg).putDouble(reference.altitudeM)
        b.putVec3Double(reference.velocityNedMps)
        b.putDouble(reference.bodyToNed.w).putDouble(reference.bodyToNed.x)
            .putDouble(reference.bodyToNed.y).putDouble(reference.bodyToNed.z)
        b.putVec3Double(reference.angularRateBodyRadps)
        b.putVec3Float(reference.velocityStdNedMps)
        b.putVec3Float(reference.attitudeStdDeg)
        b.putLong(reference.timeUncertaintyNanos)
        return finish(b)
    }

    fun encodeSyncRequest(sequence: Long, phoneSendNanos: Long): ByteArray {
        val b = allocate(TYPE_SYNC_REQUEST, SYNC_REQUEST_PAYLOAD_BYTES, sequence, phoneSendNanos, 0L)
        b.putLong(phoneSendNanos)
        return finish(b)
    }

    fun encodeSyncResponse(sequence: Long, phoneSendNanos: Long, masterReceiveNanos: Long, masterSendNanos: Long): ByteArray {
        val b = allocate(TYPE_SYNC_RESPONSE, SYNC_RESPONSE_PAYLOAD_BYTES, sequence, masterSendNanos, 0L)
        b.putLong(phoneSendNanos).putLong(masterReceiveNanos).putLong(masterSendNanos)
        return finish(b)
    }

    fun decode(frame: ByteArray): Message {
        val h = verifyAndHeader(frame)
        val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).apply { position(HEADER_BYTES) }
        return when (h.type) {
            TYPE_REFERENCE -> {
                require(h.payloadBytes == REFERENCE_PAYLOAD_BYTES)
                val flags = b.int.also { b.int }
                val position = Vec3(b.double, b.double, b.double)
                val velocity = b.getVec3Double()
                val quaternion = Quaternion(b.double, b.double, b.double, b.double).normalized()
                val rate = b.getVec3Double()
                val velocityStd = b.getVec3Float()
                val attitudeStd = b.getVec3Float()
                val uncertainty = b.long
                Message.Reference(
                    MasterInsReference(
                        h.sequence, h.monotonicNanos, h.utcNanos, 0L, flags,
                        position.x, position.y, position.z, velocity, quaternion, rate,
                        velocityStd, attitudeStd, uncertainty,
                    ),
                )
            }
            TYPE_SYNC_REQUEST -> Message.SyncRequest(h.sequence, b.long)
            TYPE_SYNC_RESPONSE -> Message.SyncResponse(h.sequence, b.long, b.long, b.long)
            else -> error("Unsupported MST1 message type ${h.type}")
        }
    }

    fun frameLength(prefix: ByteArray): Int {
        require(prefix.size >= 12)
        require(prefix.copyOfRange(0, 4).contentEquals(MAGIC))
        val b = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN)
        val header = b.getShort(6).toInt() and 0xffff
        val payload = b.getShort(8).toInt() and 0xffff
        require(header == HEADER_BYTES && payload <= 4096)
        return header + payload + 4
    }

    private fun allocate(type: Byte, payload: Int, sequence: Long, monotonic: Long, utc: Long): ByteBuffer =
        ByteBuffer.allocate(HEADER_BYTES + payload + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC).put(VERSION).put(type).putShort(HEADER_BYTES.toShort()).putShort(payload.toShort()).putShort(0)
            putInt(sequence.toInt()).putLong(monotonic).putLong(utc)
        }

    private fun finish(b: ByteBuffer): ByteArray {
        check(b.position() == b.capacity() - 4)
        b.putInt(CRC32().apply { update(b.array(), 0, b.position()) }.value.toInt())
        return b.array()
    }

    private fun verifyAndHeader(frame: ByteArray): Header {
        require(frame.size >= HEADER_BYTES + 4)
        require(frame.copyOfRange(0, 4).contentEquals(MAGIC)) { "Invalid MST1 magic" }
        val expected = ByteBuffer.wrap(frame, frame.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
        val actual = CRC32().apply { update(frame, 0, frame.size - 4) }.value
        require(expected == actual) { "MST1 CRC mismatch" }
        val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        b.position(4)
        require(b.get() == VERSION)
        val type = b.get()
        val header = b.short.toInt() and 0xffff
        val payload = b.short.toInt() and 0xffff
        b.short
        val sequence = b.int.toLong() and 0xffff_ffffL
        val monotonic = b.long
        val utc = b.long
        require(header == HEADER_BYTES && frame.size == header + payload + 4)
        return Header(type, payload, sequence, monotonic, utc)
    }

    private fun ByteBuffer.putVec3Double(v: Vec3) { putDouble(v.x); putDouble(v.y); putDouble(v.z) }
    private fun ByteBuffer.putVec3Float(v: Vec3) { putFloat(v.x.toFloat()); putFloat(v.y.toFloat()); putFloat(v.z.toFloat()) }
    private fun ByteBuffer.getVec3Double() = Vec3(double, double, double)
    private fun ByteBuffer.getVec3Float() = Vec3(float.toDouble(), float.toDouble(), float.toDouble())
}

/** Four-timestamp NTP estimate, expressed as master clock minus phone clock. */
class MasterClockSynchronizer {
    var offsetNanos: Long = 0L
        private set
    var uncertaintyNanos: Long = Long.MAX_VALUE
        private set

    fun observe(phoneSend: Long, masterReceive: Long, masterSend: Long, phoneReceive: Long): Boolean {
        if (phoneReceive <= phoneSend || masterSend < masterReceive) return false
        val roundTrip = (phoneReceive - phoneSend) - (masterSend - masterReceive)
        if (roundTrip < 0L) return false
        val candidateOffset = ((masterReceive - phoneSend) + (masterSend - phoneReceive)) / 2L
        val candidateUncertainty = roundTrip / 2L
        if (candidateUncertainty < uncertaintyNanos) {
            offsetNanos = candidateOffset
            uncertaintyNanos = candidateUncertainty
        }
        return uncertaintyNanos < 10_000_000L
    }

    fun masterToPhone(masterNanos: Long): Long = masterNanos - offsetNanos
}
