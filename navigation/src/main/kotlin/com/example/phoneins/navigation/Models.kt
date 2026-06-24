package com.example.phoneins.navigation

import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    operator fun div(scale: Double) = Vec3(x / scale, y / scale, z / scale)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 = norm().takeIf { it > 1e-12 }?.let { this / it } ?: ZERO
    fun toArray() = doubleArrayOf(x, y, z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        fun from(values: DoubleArray, offset: Int = 0) = Vec3(values[offset], values[offset + 1], values[offset + 2])
    }
}

data class TimedImuSample(
    val monotonicNanos: Long,
    val accelerationMps2: Vec3,
    val angularRateRadps: Vec3,
    val fresh: Boolean = true,
)

data class MagSample(val monotonicNanos: Long, val fieldMicroTesla: Vec3)

data class GnssFix(
    val monotonicNanos: Long,
    val unixMillis: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeEllipsoidM: Double,
    val velocityNedMps: Vec3,
    val horizontalAccuracyM: Double,
    val verticalAccuracyM: Double,
    val speedAccuracyMps: Double,
    val bearingAccuracyDeg: Double,
    val satellitesUsed: Int,
    val hasAltitude: Boolean = true,
    val hasHorizontalVelocity: Boolean = true,
    val hasDerivedDownVelocity: Boolean = false,
    val isMock: Boolean = false,
)

enum class MotionProfile { VEHICLE, PEDESTRIAN }
enum class InitSource { NONE, AUTOMATIC, MANUAL }
enum class FixStatus { NO_FIX, FIX_2D, FIX_3D, STALE }

data class ManualInitialization(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeEllipsoidM: Double,
    val rollDeg: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
)

object QualityFlag {
    const val INITIALIZED = 1 shl 0
    const val DEGRADED = 1 shl 1
    const val IMU_FRESH = 1 shl 2
    const val IMU_HELD = 1 shl 3
    const val GNSS_POSITION_VALID = 1 shl 4
    const val GNSS_HORIZONTAL_VELOCITY_VALID = 1 shl 5
    const val GNSS_DOWN_VELOCITY_VALID = 1 shl 6
    const val GNSS_FRESH = 1 shl 7
    const val HEADING_COURSE_AIDED = 1 shl 8
    const val DEAD_RECKONING = 1 shl 9
    const val MANUAL_INITIALIZATION = 1 shl 10
    const val OUTPUT_OVERRUN = 1 shl 11
    const val MOCK_GNSS = 1 shl 12
    const val MAGNETOMETER_INITIALIZATION = 1 shl 13
    const val STATIONARY_CONSTRAINT = 1 shl 14
    const val GNSS_UPDATE_REJECTED = 1 shl 15
    const val GNSS_AIDING_SUSPENDED = 1 shl 16
}

data class NavigationSolution(
    val monotonicNanos: Long,
    val unixMillis: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeEllipsoidM: Double,
    val velocityNedMps: Vec3,
    val eulerDeg: Vec3,
    val positionStdNedM: Vec3,
    val velocityStdNedMps: Vec3,
    val attitudeStdDeg: Vec3,
    val latestImu: TimedImuSample?,
    val latestGnss: GnssFix?,
    val initializationSource: InitSource,
    val motionProfile: MotionProfile,
    val fixStatus: FixStatus,
    val qualityFlags: Int,
    val sequence: Long = 0,
    val accelerometerBiasMps2: Vec3 = Vec3.ZERO,
    val gyroBiasRadps: Vec3 = Vec3.ZERO,
) {
    val initialized get() = qualityFlags and QualityFlag.INITIALIZED != 0
    val degraded get() = qualityFlags and QualityFlag.DEGRADED != 0

    companion object {
        fun empty(nowNanos: Long = 0, nowMillis: Long = 0) = NavigationSolution(
            nowNanos, nowMillis, Double.NaN, Double.NaN, Double.NaN,
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
            null, null, InitSource.NONE, MotionProfile.VEHICLE, FixStatus.NO_FIX, 0,
        )
    }
}
