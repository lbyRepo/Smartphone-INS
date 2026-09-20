package com.example.phoneins.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.phoneins.data.TrialLogSummary
import com.example.phoneins.navigation.MotionProfile
import com.example.phoneins.navigation.QualityFlag
import com.example.phoneins.navigation.Vec3
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class CsvReplayRow(
    val imuFresh: Boolean = true,
    val imuClipped: Boolean = false,
    val sequence: Long,
    val monotonicNanos: Long,
    val unixMillis: Long,
    val initialized: Boolean,
    val motionProfile: MotionProfile,
    val insPosition: Vec3,
    val insVelocityNed: Vec3,
    val insEulerDeg: Vec3,
    val accelerometerBias: Vec3,
    val gyroBias: Vec3,
    val imuMonotonicNanos: Long,
    val acceleration: Vec3,
    val angularRate: Vec3,
    val gnssMonotonicNanos: Long,
    val gnssUnixMillis: Long,
    val gnssPosition: Vec3,
    val gnssVelocityNed: Vec3,
    val gnssHorizontalAccuracy: Double,
    val gnssVerticalAccuracy: Double,
    val gnssSpeedAccuracy: Double,
    val gnssBearingAccuracy: Double,
    val gnssSatellites: Int,
    val gnssHasAltitude: Boolean,
    val gnssHasHorizontalVelocity: Boolean,
    val gnssHasDerivedDownVelocity: Boolean,
    val gnssIsMock: Boolean,
)

internal class CsvReplayIndex private constructor(
    val file: File,
    private val columns: Map<String, Int>,
    private val offsets: LongArray,
    val firstMonotonicNanos: Long,
    val lastMonotonicNanos: Long,
    val initializationRow: Int,
) {
    val rowCount: Int get() = offsets.size
    val durationMillis: Long get() = ((lastMonotonicNanos - firstMonotonicNanos).coerceAtLeast(0L) / 1_000_000L)

    fun row(index: Int): CsvReplayRow {
        require(index in offsets.indices)
        return RandomAccessFile(file, "r").use { input ->
            input.seek(offsets[index])
            parseRow(input.readLine() ?: error("CSV ended before row ${index + 2}"), columns, index + 2)
        }
    }

    fun cursor(startIndex: Int = 0) = CsvReplayCursor(file, columns, offsets, startIndex)

    fun findRowAt(positionMillis: Long): Int {
        val target = firstMonotonicNanos + positionMillis.coerceIn(0L, durationMillis) * 1_000_000L
        var low = 0
        var high = offsets.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (row(mid).monotonicNanos <= target) low = mid else high = mid - 1
        }
        return low
    }

    fun summary(): TrialLogSummary = TrialLogSummary(
        id = file.canonicalPath,
        displayName = file.name,
        modifiedMillis = file.lastModified(),
        durationMillis = durationMillis,
        rowCount = rowCount,
        sizeBytes = file.length(),
    )

    companion object {
        fun open(file: File): CsvReplayIndex {
            require(file.isFile) { "CSV file does not exist" }
            RandomAccessFile(file, "r").use { input ->
                val headerLine = input.readLine() ?: error("CSV is empty")
                val names = headerLine.split(',').map { it.trim() }
                val columns = names.withIndex().associate { it.value to it.index }
                val missing = REQUIRED_COLUMNS.filterNot(columns::containsKey)
                require(missing.isEmpty()) { "CSV is missing required columns: ${missing.joinToString()}" }

                val offsets = ArrayList<Long>()
                var firstTime = Long.MIN_VALUE
                var previousTime = Long.MIN_VALUE
                var lastTime = Long.MIN_VALUE
                var initializationRow = -1
                var rowNumber = 2
                while (true) {
                    val offset = input.filePointer
                    val line = input.readLine() ?: break
                    if (line.isBlank()) { rowNumber++; continue }
                    val row = parseRow(line, columns, rowNumber)
                    require(previousTime == Long.MIN_VALUE || row.monotonicNanos >= previousTime) {
                        "CSV time moves backwards at row $rowNumber"
                    }
                    if (firstTime == Long.MIN_VALUE) firstTime = row.monotonicNanos
                    previousTime = row.monotonicNanos
                    lastTime = row.monotonicNanos
                    if (initializationRow < 0 && row.initialized) initializationRow = offsets.size
                    offsets += offset
                    rowNumber++
                }
                require(offsets.isNotEmpty()) { "CSV contains no data rows" }
                require(initializationRow >= 0) { "CSV contains no initialized INS solution" }
                writeMetadata(file, offsets.size)
                return CsvReplayIndex(file, columns, offsets.toLongArray(), firstTime, lastTime, initializationRow)
            }
        }

        fun quickSummary(file: File): TrialLogSummary {
            RandomAccessFile(file, "r").use { input ->
                val headerLine = input.readLine() ?: error("CSV is empty")
                val columns = headerLine.split(',').map { it.trim() }.withIndex().associate { it.value to it.index }
                val missing = REQUIRED_COLUMNS.filterNot(columns::containsKey)
                require(missing.isEmpty()) { "CSV is missing required columns" }
                var firstLine: String? = null
                while (firstLine == null) firstLine = input.readLine()?.takeIf(String::isNotBlank) ?: if (input.filePointer >= input.length()) break else null
                val first = parseRow(requireNotNull(firstLine) { "CSV contains no data rows" }, columns, 2)
                val lastLine = readLastNonBlankLine(file)
                val last = parseRow(lastLine, columns, -1)
                return TrialLogSummary(
                    id = file.canonicalPath,
                    displayName = file.name,
                    modifiedMillis = file.lastModified(),
                    durationMillis = ((last.monotonicNanos - first.monotonicNanos).coerceAtLeast(0L) / 1_000_000L),
                    rowCount = readCachedRowCount(file),
                    sizeBytes = file.length(),
                )
            }
        }

        private fun metadataFile(file: File) = File(file.parentFile, ".${file.name}.replaymeta")

        private fun writeMetadata(file: File, rowCount: Int) {
            runCatching { metadataFile(file).writeText("${file.length()},${file.lastModified()},$rowCount") }
        }

        private fun readCachedRowCount(file: File): Int {
            val parts = runCatching { metadataFile(file).readText().trim().split(',') }.getOrNull() ?: return -1
            if (parts.size != 3 || parts[0].toLongOrNull() != file.length() || parts[1].toLongOrNull() != file.lastModified()) return -1
            return parts[2].toIntOrNull() ?: -1
        }

        private fun readLastNonBlankLine(file: File): String {
            RandomAccessFile(file, "r").use { input ->
                var position = input.length() - 1L
                val reversed = StringBuilder()
                while (position >= 0L) {
                    input.seek(position--)
                    val char = input.read().toChar()
                    if ((char == '\n' || char == '\r') && reversed.isNotEmpty()) {
                        val line = reversed.reverse().toString().trim()
                        if (line.isNotEmpty()) return line
                        reversed.clear()
                    } else if (char != '\n' && char != '\r') {
                        reversed.append(char)
                    }
                }
                return reversed.reverse().toString().trim().takeIf(String::isNotEmpty)
                    ?: error("CSV contains no data rows")
            }
        }

        private val REQUIRED_COLUMNS = setOf(
            "sequence", "monotonic_nanos", "unix_millis", "motion_profile", "initialized",
            "ins_latitude_deg", "ins_longitude_deg", "ins_altitude_m",
            "ins_velocity_north_mps", "ins_velocity_east_mps", "ins_velocity_down_mps",
            "ins_roll_deg", "ins_pitch_deg", "ins_yaw_deg",
            "ins_accel_bias_x_mps2", "ins_accel_bias_y_mps2", "ins_accel_bias_z_mps2",
            "ins_gyro_bias_x_radps", "ins_gyro_bias_y_radps", "ins_gyro_bias_z_radps",
            "imu_monotonic_nanos", "imu_accel_x_mps2", "imu_accel_y_mps2", "imu_accel_z_mps2",
            "imu_gyro_x_radps", "imu_gyro_y_radps", "imu_gyro_z_radps",
            "gnss_monotonic_nanos", "gnss_unix_millis", "gnss_latitude_deg", "gnss_longitude_deg",
            "gnss_altitude_m", "gnss_velocity_north_mps", "gnss_velocity_east_mps", "gnss_velocity_down_mps",
            "gnss_horizontal_accuracy_m", "gnss_vertical_accuracy_m", "gnss_speed_accuracy_mps",
            "gnss_bearing_accuracy_deg", "gnss_satellites_used", "gnss_has_altitude",
            "gnss_has_horizontal_velocity", "gnss_has_derived_down_velocity", "gnss_is_mock",
        )

        internal fun parseRow(line: String, columns: Map<String, Int>, rowNumber: Int): CsvReplayRow {
            val values = line.split(',')
            fun raw(name: String): String = values.getOrNull(columns.getValue(name))
                ?: error("Row $rowNumber is truncated at $name")
            fun long(name: String) = raw(name).toLongOrNull() ?: error("Invalid $name at row $rowNumber")
            fun int(name: String) = raw(name).toIntOrNull() ?: error("Invalid $name at row $rowNumber")
            fun double(name: String) = raw(name).toDoubleOrNull() ?: error("Invalid $name at row $rowNumber")
            fun flag(name: String) = when (raw(name)) {
                "1", "true", "TRUE" -> true
                "0", "false", "FALSE" -> false
                else -> error("Invalid $name at row $rowNumber")
            }
            fun vec(x: String, y: String, z: String) = Vec3(double(x), double(y), double(z))

            val initialized = flag("initialized")
            val insPosition = vec("ins_latitude_deg", "ins_longitude_deg", "ins_altitude_m")
            val insEuler = vec("ins_roll_deg", "ins_pitch_deg", "ins_yaw_deg")
            if (initialized) {
                require(insPosition.x.isFinite() && insPosition.y.isFinite() && insPosition.z.isFinite()) {
                    "Initialized INS position is invalid at row $rowNumber"
                }
                require(insEuler.x.isFinite() && insEuler.y.isFinite() && insEuler.z.isFinite()) {
                    "Initialized Euler angles are invalid at row $rowNumber"
                }
            }
            return CsvReplayRow(
                imuClipped = columns["quality_flags"]?.let { column ->
                    val flags = values.getOrNull(column)?.toIntOrNull() ?: error("Invalid quality_flags at row $rowNumber")
                    flags and QualityFlag.IMU_CLIPPED != 0
                } ?: false,
                imuFresh = columns["quality_flags"]?.let { column ->
                    val flags = values.getOrNull(column)?.toIntOrNull() ?: error("Invalid quality_flags at row $rowNumber")
                    flags and QualityFlag.IMU_HELD == 0
                } ?: true,
                sequence = long("sequence"),
                monotonicNanos = long("monotonic_nanos"),
                unixMillis = long("unix_millis"),
                initialized = initialized,
                motionProfile = runCatching { MotionProfile.valueOf(raw("motion_profile")) }
                    .getOrElse { error("Invalid motion_profile at row $rowNumber") },
                insPosition = insPosition,
                insVelocityNed = vec("ins_velocity_north_mps", "ins_velocity_east_mps", "ins_velocity_down_mps"),
                insEulerDeg = insEuler,
                accelerometerBias = vec("ins_accel_bias_x_mps2", "ins_accel_bias_y_mps2", "ins_accel_bias_z_mps2"),
                gyroBias = vec("ins_gyro_bias_x_radps", "ins_gyro_bias_y_radps", "ins_gyro_bias_z_radps"),
                imuMonotonicNanos = long("imu_monotonic_nanos"),
                acceleration = vec("imu_accel_x_mps2", "imu_accel_y_mps2", "imu_accel_z_mps2"),
                angularRate = vec("imu_gyro_x_radps", "imu_gyro_y_radps", "imu_gyro_z_radps"),
                gnssMonotonicNanos = long("gnss_monotonic_nanos"),
                gnssUnixMillis = long("gnss_unix_millis"),
                gnssPosition = vec("gnss_latitude_deg", "gnss_longitude_deg", "gnss_altitude_m"),
                gnssVelocityNed = vec("gnss_velocity_north_mps", "gnss_velocity_east_mps", "gnss_velocity_down_mps"),
                gnssHorizontalAccuracy = double("gnss_horizontal_accuracy_m"),
                gnssVerticalAccuracy = double("gnss_vertical_accuracy_m"),
                gnssSpeedAccuracy = double("gnss_speed_accuracy_mps"),
                gnssBearingAccuracy = double("gnss_bearing_accuracy_deg"),
                gnssSatellites = int("gnss_satellites_used"),
                gnssHasAltitude = flag("gnss_has_altitude"),
                gnssHasHorizontalVelocity = flag("gnss_has_horizontal_velocity"),
                gnssHasDerivedDownVelocity = flag("gnss_has_derived_down_velocity"),
                gnssIsMock = flag("gnss_is_mock"),
            )
        }
    }
}

internal class CsvReplayCursor(
    file: File,
    private val columns: Map<String, Int>,
    private val offsets: LongArray,
    startIndex: Int,
) : AutoCloseable {
    private val input = RandomAccessFile(file, "r")
    var index: Int = startIndex
        private set

    fun next(): CsvReplayRow? {
        if (index !in offsets.indices) return null
        input.seek(offsets[index])
        val rowNumber = index + 2
        val row = CsvReplayIndex.parseRow(input.readLine() ?: error("CSV ended before row $rowNumber"), columns, rowNumber)
        index++
        return row
    }

    override fun close() = input.close()
}

internal class TrialLogCatalog(private val context: Context) {
    private val importsDirectory = File(context.filesDir, "replay_imports").apply { mkdirs() }
    private val trialDirectory get() = CsvTrialLogger.trialLogDirectory(context)

    fun recent(activeFile: File? = null): List<TrialLogSummary> {
        val activePath = activeFile?.canonicalPath
        return (trialDirectory.listFiles()?.toList().orEmpty() + importsDirectory.listFiles()?.toList().orEmpty())
            .asSequence()
            .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) && it.canonicalPath != activePath }
            .sortedByDescending(File::lastModified)
            .mapNotNull { runCatching { CsvReplayIndex.quickSummary(it) }.getOrNull() }
            .take(10)
            .toList()
    }

    fun import(uri: Uri): File {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "imported_trial.csv"
        val safeStem = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80).ifEmpty { "imported_trial" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        var output = File(importsDirectory, "${safeStem}_$stamp.csv")
        var suffix = 1
        while (output.exists()) output = File(importsDirectory, "${safeStem}_${stamp}_${suffix++}.csv")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected CSV" }
            output.outputStream().buffered().use(input::copyTo)
        }
        CsvReplayIndex.open(output)
        return output
    }

    fun resolve(id: String): File {
        val file = File(id).canonicalFile
        val allowed = listOf(trialDirectory.canonicalFile, importsDirectory.canonicalFile)
            .any { directory -> file.path.startsWith(directory.path + File.separator) }
        require(allowed && file.isFile) { "Selected replay log is unavailable" }
        return file
    }
}
