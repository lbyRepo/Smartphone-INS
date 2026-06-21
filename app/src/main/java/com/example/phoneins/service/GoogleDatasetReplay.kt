package com.example.phoneins.service

import android.content.Context
import com.example.phoneins.navigation.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** Replays the packaged public Pixel 4 fixture through the same callbacks as live hardware. */
class GoogleDatasetReplay(private val context: Context) {
    suspend fun run(
        initialize: (ManualInitialization) -> Unit,
        onImu: (TimedImuSample) -> Unit,
        onMag: (MagSample) -> Unit,
        onGnss: (GnssFix) -> Unit,
    ) {
        val truthLine = context.assets.open("google_decimeter_2021/Pixel4_ground_truth.csv").bufferedReader().useLines { it.drop(1).first() }
        val truth = truthLine.split(',')
        initialize(ManualInitialization(truth[3].toDouble(), truth[4].toDouble(), truth[5].toDouble(), 0.0, 0.0, truth[10].toDouble()))

        val lines = context.assets.open("google_decimeter_2021/Pixel4_GnssLog.txt").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .mapNotNull { line ->
                val f = line.split(',')
                when (f[0]) {
                    "Accel", "Gyro", "Mag" -> f[1].toLong() to f
                    "Fix" -> if (f[1] == "GPS") f[8].toLong() to f else null
                    else -> null
                }
            }.sortedBy { it.first }
        if (lines.isEmpty()) return
        var previousUnix = lines.first().first
        var firstSensorUnix = lines.first().first
        val replayStartMonotonic = android.os.SystemClock.elapsedRealtimeNanos()
        var acceleration = Vec3(0.0, 0.0, -9.80665)
        for ((unix, f) in lines) {
            delay((unix - previousUnix).coerceIn(0, 1_000))
            previousUnix = unix
            when (f[0]) {
                "Accel" -> {
                    val native = Vec3(f[3].toDouble(), f[4].toDouble(), f[5].toDouble())
                    acceleration = Vec3(native.y, native.x, -native.z)
                }
                "Gyro" -> {
                    val native = Vec3(f[3].toDouble(), f[4].toDouble(), f[5].toDouble())
                    val monotonic = replayStartMonotonic + (unix - firstSensorUnix) * 1_000_000
                    onImu(TimedImuSample(monotonic, acceleration, Vec3(native.y, native.x, -native.z)))
                }
                "Mag" -> {
                    val native = Vec3(f[3].toDouble(), f[4].toDouble(), f[5].toDouble())
                    val monotonic = replayStartMonotonic + (unix - firstSensorUnix) * 1_000_000
                    onMag(MagSample(monotonic, Vec3(native.y, native.x, -native.z)))
                }
                "Fix" -> {
                    val speed = f[5].toDouble(); val bearing = Math.toRadians(f[7].toDouble())
                    val monotonic = replayStartMonotonic + (unix - firstSensorUnix) * 1_000_000
                    onGnss(GnssFix(
                        monotonic, unix, f[2].toDouble(), f[3].toDouble(), f[4].toDouble(),
                        Vec3(speed * cos(bearing), speed * sin(bearing), Double.NaN),
                        f[6].toDouble(), 50.0, f[9].toDouble().coerceAtLeast(0.1),
                        f[10].toDouble().takeIf { it > 0 } ?: 180.0, 0,
                        hasHorizontalVelocity = speed > 0.05, isMock = true,
                    ))
                }
            }
        }
    }
}
