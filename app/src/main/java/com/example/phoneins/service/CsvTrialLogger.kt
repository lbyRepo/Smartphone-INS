package com.example.phoneins.service

import android.content.Context
import android.os.Environment
import com.example.phoneins.navigation.NavigationSolution
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class CsvTrialLogger(private val context: Context) {
    private val directory: File
        get() = trialLogDirectory(context)

    @Volatile
    private var writer: BufferedWriter? = null
    private var rowsWritten = 0L
    var currentFile: File? = null
        private set
    val logging get() = writer != null

    @Synchronized
    fun start(filePrefix: String = "phoneins_trial"): File {
        stop()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val safePrefix = filePrefix.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(96).trim('_').ifEmpty { "phoneins_trial" }
        val file = uniqueFile(directory, "${safePrefix}_$stamp", ".csv")
        writer = file.outputStream().buffered(256 * 1024).writer(Charsets.UTF_8).buffered(256 * 1024)
        currentFile = file
        rowsWritten = 0L
        writer?.appendLine(HEADER)
        return file
    }

    @Synchronized
    fun append(solution: NavigationSolution) {
        val out = writer ?: return
        out.appendLine(solution.toCsvRow())
        rowsWritten++
    }

    @Synchronized
    fun flush() {
        writer?.flush()
    }

    @Synchronized
    fun stop() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    private fun NavigationSolution.toCsvRow(): String {
        val imu = latestImu
        val gnss = latestGnss
        val gnssAgeMs = gnss?.let {
            ((monotonicNanos - it.monotonicNanos).coerceAtLeast(0L) / 1_000_000L).toDouble()
        } ?: Double.NaN
        return buildString(2048) {
            add(sequence)
            add(monotonicNanos)
            add(unixMillis)
            addIso(unixMillis)
            add(qualityFlags)
            add(initializationSource.name)
            add(motionProfile.name)
            add(fixStatus.name)
            add(if (initialized) 1 else 0)
            add(latitudeDeg)
            add(longitudeDeg)
            add(altitudeEllipsoidM)
            add(velocityNedMps.x)
            add(velocityNedMps.y)
            add(velocityNedMps.z)
            add(eulerDeg.x)
            add(eulerDeg.y)
            add(eulerDeg.z)
            add(positionStdNedM.x)
            add(positionStdNedM.y)
            add(positionStdNedM.z)
            add(velocityStdNedMps.x)
            add(velocityStdNedMps.y)
            add(velocityStdNedMps.z)
            add(attitudeStdDeg.x)
            add(attitudeStdDeg.y)
            add(attitudeStdDeg.z)
            add(accelerometerBiasMps2.x)
            add(accelerometerBiasMps2.y)
            add(accelerometerBiasMps2.z)
            add(gyroBiasRadps.x)
            add(gyroBiasRadps.y)
            add(gyroBiasRadps.z)
            add(imu?.monotonicNanos ?: 0L)
            add(imu?.accelerationMps2?.x ?: Double.NaN)
            add(imu?.accelerationMps2?.y ?: Double.NaN)
            add(imu?.accelerationMps2?.z ?: Double.NaN)
            add(imu?.angularRateRadps?.x ?: Double.NaN)
            add(imu?.angularRateRadps?.y ?: Double.NaN)
            add(imu?.angularRateRadps?.z ?: Double.NaN)
            add(gnss?.monotonicNanos ?: 0L)
            add(gnssAgeMs)
            add(gnss?.unixMillis ?: 0L)
            add(gnss?.latitudeDeg ?: Double.NaN)
            add(gnss?.longitudeDeg ?: Double.NaN)
            add(gnss?.altitudeEllipsoidM ?: Double.NaN)
            add(gnss?.velocityNedMps?.x ?: Double.NaN)
            add(gnss?.velocityNedMps?.y ?: Double.NaN)
            add(gnss?.velocityNedMps?.z ?: Double.NaN)
            add(gnss?.horizontalAccuracyM ?: Double.NaN)
            add(gnss?.verticalAccuracyM ?: Double.NaN)
            add(gnss?.speedAccuracyMps ?: Double.NaN)
            add(gnss?.bearingAccuracyDeg ?: Double.NaN)
            add(gnss?.satellitesUsed ?: 0)
            add(if (gnss?.hasAltitude == true) 1 else 0)
            add(if (gnss?.hasHorizontalVelocity == true) 1 else 0)
            add(if (gnss?.hasDerivedDownVelocity == true) 1 else 0)
            add(if (gnss?.isMock == true) 1 else 0)
        }
    }

    private fun StringBuilder.add(value: String) {
        if (isNotEmpty()) append(',')
        append(value)
    }

    private fun StringBuilder.add(value: Long) = add(value.toString())
    private fun StringBuilder.add(value: Int) = add(value.toString())
    private fun StringBuilder.add(value: Double) = add(if (value.isFinite()) value.toString() else "NaN")

    private fun StringBuilder.addIso(unixMillis: Long) {
        add(runCatching { DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(unixMillis)) }.getOrDefault(""))
    }

    companion object {
        fun trialLogDirectory(context: Context): File = (
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(context.filesDir, "documents")
            ).resolve("trial_logs").apply { mkdirs() }

        private fun uniqueFile(directory: File, stem: String, extension: String): File {
            var candidate = File(directory, stem + extension)
            var suffix = 1
            while (candidate.exists()) candidate = File(directory, "${stem}_${suffix++}$extension")
            return candidate
        }

        val HEADER = listOf(
            "sequence",
            "monotonic_nanos",
            "unix_millis",
            "utc_time_iso",
            "quality_flags",
            "initialization_source",
            "motion_profile",
            "fix_status",
            "initialized",
            "ins_latitude_deg",
            "ins_longitude_deg",
            "ins_altitude_m",
            "ins_velocity_north_mps",
            "ins_velocity_east_mps",
            "ins_velocity_down_mps",
            "ins_roll_deg",
            "ins_pitch_deg",
            "ins_yaw_deg",
            "ins_position_std_north_m",
            "ins_position_std_east_m",
            "ins_position_std_down_m",
            "ins_velocity_std_north_mps",
            "ins_velocity_std_east_mps",
            "ins_velocity_std_down_mps",
            "ins_attitude_std_roll_deg",
            "ins_attitude_std_pitch_deg",
            "ins_attitude_std_yaw_deg",
            "ins_accel_bias_x_mps2",
            "ins_accel_bias_y_mps2",
            "ins_accel_bias_z_mps2",
            "ins_gyro_bias_x_radps",
            "ins_gyro_bias_y_radps",
            "ins_gyro_bias_z_radps",
            "imu_monotonic_nanos",
            "imu_accel_x_mps2",
            "imu_accel_y_mps2",
            "imu_accel_z_mps2",
            "imu_gyro_x_radps",
            "imu_gyro_y_radps",
            "imu_gyro_z_radps",
            "gnss_monotonic_nanos",
            "gnss_age_ms",
            "gnss_unix_millis",
            "gnss_latitude_deg",
            "gnss_longitude_deg",
            "gnss_altitude_m",
            "gnss_velocity_north_mps",
            "gnss_velocity_east_mps",
            "gnss_velocity_down_mps",
            "gnss_horizontal_accuracy_m",
            "gnss_vertical_accuracy_m",
            "gnss_speed_accuracy_mps",
            "gnss_bearing_accuracy_deg",
            "gnss_satellites_used",
            "gnss_has_altitude",
            "gnss_has_horizontal_velocity",
            "gnss_has_derived_down_velocity",
            "gnss_is_mock",
        ).joinToString(",")
    }
}
