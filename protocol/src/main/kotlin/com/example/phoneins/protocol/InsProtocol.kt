package com.example.phoneins.protocol

import com.example.phoneins.navigation.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

object InsProtocol {
    const val VERSION: Byte = 1
    const val MESSAGE_SOLUTION: Byte = 1
    const val HEADER_BYTES = 36
    const val PAYLOAD_BYTES = 184
    const val FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + 4
    val MAGIC = byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())

    fun encode(solution: NavigationSolution): ByteArray {
        val buffer = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(VERSION)
        buffer.put(MESSAGE_SOLUTION)
        buffer.putShort(HEADER_BYTES.toShort())
        buffer.putShort(PAYLOAD_BYTES.toShort())
        buffer.putShort(0)
        buffer.putInt(solution.sequence.toInt())
        buffer.putLong(solution.monotonicNanos)
        buffer.putLong(solution.unixMillis)
        buffer.putInt(solution.qualityFlags)

        buffer.put(solution.initializationSource.ordinal.toByte())
        buffer.put(solution.motionProfile.ordinal.toByte())
        buffer.put(solution.fixStatus.ordinal.toByte())
        buffer.put((solution.latestGnss?.satellitesUsed ?: 0).coerceIn(0, 255).toByte())
        val ageMs = solution.latestGnss?.let {
            ((solution.monotonicNanos - it.monotonicNanos).coerceAtLeast(0) / 1_000_000).coerceAtMost(0xffff_ffffL)
        } ?: 0xffff_ffffL
        buffer.putInt(ageMs.toInt())

        buffer.putDouble(solution.latitudeDeg)
        buffer.putDouble(solution.longitudeDeg)
        buffer.putDouble(solution.altitudeEllipsoidM)
        buffer.putVec3Float(solution.velocityNedMps)
        buffer.putVec3Float(solution.eulerDeg)
        buffer.putVec3Float(solution.positionStdNedM)
        buffer.putVec3Float(solution.velocityStdNedMps)
        buffer.putVec3Float(solution.attitudeStdDeg)

        val imu = solution.latestImu
        buffer.putLong(imu?.monotonicNanos ?: 0)
        buffer.putVec3Float(imu?.accelerationMps2 ?: NAN_VEC)
        buffer.putVec3Float(imu?.angularRateRadps ?: NAN_VEC)

        val gnss = solution.latestGnss
        buffer.putLong(gnss?.monotonicNanos ?: 0)
        buffer.putDouble(gnss?.latitudeDeg ?: Double.NaN)
        buffer.putDouble(gnss?.longitudeDeg ?: Double.NaN)
        buffer.putDouble(gnss?.altitudeEllipsoidM ?: Double.NaN)
        buffer.putVec3Float(gnss?.velocityNedMps ?: NAN_VEC)
        buffer.putFloat((gnss?.horizontalAccuracyM ?: Double.NaN).toFloat())
        buffer.putFloat((gnss?.verticalAccuracyM ?: Double.NaN).toFloat())
        buffer.putFloat((gnss?.speedAccuracyMps ?: Double.NaN).toFloat())
        buffer.putFloat((gnss?.bearingAccuracyDeg ?: Double.NaN).toFloat())

        check(buffer.position() == HEADER_BYTES + PAYLOAD_BYTES)
        val crc = CRC32().apply { update(buffer.array(), 0, buffer.position()) }.value
        buffer.putInt(crc.toInt())
        return buffer.array()
    }

    fun decode(frame: ByteArray): DecodedInsPacket {
        require(frame.size == FRAME_BYTES) { "Expected $FRAME_BYTES bytes, got ${frame.size}" }
        require(frame.copyOfRange(0, 4).contentEquals(MAGIC)) { "Invalid INS frame magic" }
        val expectedCrc = ByteBuffer.wrap(frame, frame.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
        val actualCrc = CRC32().apply { update(frame, 0, frame.size - 4) }.value
        require(expectedCrc == actualCrc) { "CRC mismatch" }
        val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        b.position(4)
        val version = b.get()
        val messageType = b.get()
        require(version == VERSION && messageType == MESSAGE_SOLUTION)
        val headerLength = b.short.toInt() and 0xffff
        val payloadLength = b.short.toInt() and 0xffff
        require(headerLength == HEADER_BYTES && payloadLength == PAYLOAD_BYTES)
        b.short
        val sequence = b.int.toLong() and 0xffff_ffffL
        val monotonicNanos = b.long
        val unixMillis = b.long
        val flags = b.int
        val initSource = b.get().toInt() and 0xff
        val profile = b.get().toInt() and 0xff
        val fixStatus = b.get().toInt() and 0xff
        val satellites = b.get().toInt() and 0xff
        val gnssAgeMs = b.int.toLong() and 0xffff_ffffL
        val latitude = b.double
        val longitude = b.double
        val altitude = b.double
        val velocity = b.getVec3Float()
        val euler = b.getVec3Float()
        val positionStd = b.getVec3Float()
        val velocityStd = b.getVec3Float()
        val attitudeStd = b.getVec3Float()
        val imuTime = b.long
        val acceleration = b.getVec3Float()
        val angularRate = b.getVec3Float()
        val gnssTime = b.long
        val gnssLatitude = b.double
        val gnssLongitude = b.double
        val gnssAltitude = b.double
        val gnssVelocity = b.getVec3Float()
        val accuracies = FloatArray(4) { b.float }
        return DecodedInsPacket(
            sequence, monotonicNanos, unixMillis, flags, initSource, profile, fixStatus, satellites,
            gnssAgeMs, latitude, longitude, altitude, velocity, euler, positionStd, velocityStd,
            attitudeStd, imuTime, acceleration, angularRate, gnssTime, gnssLatitude, gnssLongitude,
            gnssAltitude, gnssVelocity, accuracies,
        )
    }

    private fun ByteBuffer.putVec3Float(value: Vec3) {
        putFloat(value.x.toFloat()); putFloat(value.y.toFloat()); putFloat(value.z.toFloat())
    }

    private fun ByteBuffer.getVec3Float() = Vec3(float.toDouble(), float.toDouble(), float.toDouble())
    private val NAN_VEC = Vec3(Double.NaN, Double.NaN, Double.NaN)
}

data class DecodedInsPacket(
    val sequence: Long,
    val monotonicNanos: Long,
    val unixMillis: Long,
    val flags: Int,
    val initializationSource: Int,
    val motionProfile: Int,
    val fixStatus: Int,
    val satellitesUsed: Int,
    val gnssAgeMillis: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeEllipsoidM: Double,
    val velocityNedMps: Vec3,
    val eulerDeg: Vec3,
    val positionStdNedM: Vec3,
    val velocityStdNedMps: Vec3,
    val attitudeStdDeg: Vec3,
    val imuMonotonicNanos: Long,
    val accelerationMps2: Vec3,
    val angularRateRadps: Vec3,
    val gnssMonotonicNanos: Long,
    val gnssLatitudeDeg: Double,
    val gnssLongitudeDeg: Double,
    val gnssAltitudeEllipsoidM: Double,
    val gnssVelocityNedMps: Vec3,
    val gnssAccuracies: FloatArray,
)
