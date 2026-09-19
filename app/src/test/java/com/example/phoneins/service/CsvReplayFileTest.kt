package com.example.phoneins.service

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CsvReplayFileTest {
    @Test
    fun preservesHeldImuFlagAndAcceptsOlderLogsWithoutQualityFlags() {
        val columns = CsvTrialLogger.HEADER.split(',')
        val held = validValues(1_000_000_000L, true) +
            ("quality_flags" to com.example.phoneins.navigation.QualityFlag.IMU_HELD.toString())
        assertEquals(false, CsvReplayIndex.open(writeCsv(columns, listOf(held))).row(0).imuFresh)
        assertEquals(true, CsvReplayIndex.open(writeCsv(columns - "quality_flags", listOf(held))).row(0).imuFresh)
    }

    @Test
    fun acceptsReorderedColumnsAndAdditionalColumns() {
        val columns = CsvTrialLogger.HEADER.split(',').reversed() + "future_field"
        val file = writeCsv(columns, listOf(validValues(0L, initialized = false), validValues(10_000_000L, initialized = true)))

        val index = CsvReplayIndex.open(file)
        assertEquals(2, index.rowCount)
        assertEquals(1, index.initializationRow)
        assertEquals(10L, index.durationMillis)
        assertEquals(1.3521, index.row(1).insPosition.x)
    }

    @Test
    fun rejectsMissingColumnTruncatedRowBackwardTimeAndMissingInitialization() {
        val all = CsvTrialLogger.HEADER.split(',')
        val missing = all.filterNot { it == "imu_accel_x_mps2" }
        assertFailsWith<IllegalArgumentException> {
            CsvReplayIndex.open(writeCsv(missing, listOf(validValues(0L, true))))
        }

        val truncated = writeCsv(all, listOf(validValues(0L, true)))
        truncated.appendText("1,2,3\n")
        assertFailsWith<IllegalStateException> { CsvReplayIndex.open(truncated) }

        assertFailsWith<IllegalArgumentException> {
            CsvReplayIndex.open(writeCsv(all, listOf(validValues(20_000_000L, true), validValues(10_000_000L, true))))
        }
        assertFailsWith<IllegalArgumentException> {
            CsvReplayIndex.open(writeCsv(all, listOf(validValues(0L, false), validValues(10_000_000L, false))))
        }
    }

    @Test
    fun indexSeeksWithoutLoadingRowsIntoMemory() {
        val columns = CsvTrialLogger.HEADER.split(',')
        val rows = (0 until 1_001).map { validValues(it * 10_000_000L, initialized = it >= 5) }
        val index = CsvReplayIndex.open(writeCsv(columns, rows))

        assertEquals(1_001, index.rowCount)
        assertEquals(500, index.findRowAt(5_000L))
        index.cursor(998).use { cursor ->
            assertEquals(998L, cursor.next()?.sequence)
            assertEquals(999L, cursor.next()?.sequence)
            assertEquals(1_000L, cursor.next()?.sequence)
            assertTrue(cursor.next() == null)
        }
    }

    private fun writeCsv(columns: List<String>, rows: List<Map<String, String>>): File {
        val file = createTempDirectory("phoneins-csv-replay").resolve("trial.csv").toFile()
        file.bufferedWriter().use { output ->
            output.appendLine(columns.joinToString(","))
            rows.forEach { values -> output.appendLine(columns.joinToString(",") { values[it] ?: "unused" }) }
        }
        return file
    }

    private fun validValues(timeNanos: Long, initialized: Boolean): Map<String, String> {
        val values = CsvTrialLogger.HEADER.split(',').associateWith { "0" }.toMutableMap()
        values["sequence"] = (timeNanos / 10_000_000L).toString()
        values["monotonic_nanos"] = timeNanos.toString()
        values["unix_millis"] = (1_750_000_000_000L + timeNanos / 1_000_000L).toString()
        values["utc_time_iso"] = "2025-06-15T00:00:00Z"
        values["initialization_source"] = if (initialized) "MANUAL" else "NONE"
        values["motion_profile"] = "VEHICLE"
        values["fix_status"] = "FIX_3D"
        values["initialized"] = if (initialized) "1" else "0"
        values["ins_latitude_deg"] = if (initialized) "1.3521" else "NaN"
        values["ins_longitude_deg"] = if (initialized) "103.8198" else "NaN"
        values["ins_altitude_m"] = if (initialized) "25.0" else "NaN"
        values["ins_roll_deg"] = "0.0"
        values["ins_pitch_deg"] = "0.0"
        values["ins_yaw_deg"] = "45.0"
        values["imu_monotonic_nanos"] = timeNanos.toString()
        values["imu_accel_z_mps2"] = "-9.80665"
        values["gnss_monotonic_nanos"] = timeNanos.toString()
        values["gnss_unix_millis"] = values.getValue("unix_millis")
        values["gnss_latitude_deg"] = "1.3521"
        values["gnss_longitude_deg"] = "103.8198"
        values["gnss_altitude_m"] = "25.0"
        values["gnss_horizontal_accuracy_m"] = "2.0"
        values["gnss_vertical_accuracy_m"] = "4.0"
        values["gnss_speed_accuracy_mps"] = "0.2"
        values["gnss_bearing_accuracy_deg"] = "5.0"
        values["gnss_satellites_used"] = "12"
        values["gnss_has_altitude"] = "1"
        values["gnss_has_horizontal_velocity"] = "1"
        return values
    }
}
