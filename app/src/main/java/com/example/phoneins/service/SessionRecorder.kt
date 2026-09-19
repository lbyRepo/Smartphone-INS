package com.example.phoneins.service

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SessionRecorder(context: Context) {
    private val directory = File(context.filesDir, "sessions").apply { mkdirs() }
    @Volatile private var output: AsyncRecordWriter<ByteArray>? = null
    val recording get() = output?.active == true
    val error get() = output?.error
    val droppedRecords get() = output?.droppedRecords ?: 0L

    fun start(): String {
        stop()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".inslog"
        output = AsyncRecordWriter {
            val stream = File(directory, name).outputStream().buffered(64 * 1024)
            object : RecordSink<ByteArray> {
                override fun write(record: ByteArray) = stream.write(record)
                override fun flush() = stream.flush()
                override fun close() = stream.close()
            }
        }
        return name
    }

    fun append(frame: ByteArray) { output?.offer(frame.copyOf()) }
    fun flush() { output?.flush() }
    fun stop() { output?.close() }
    fun sessions(): List<String> = directory.listFiles { file -> file.extension == "inslog" }
        ?.sortedByDescending { it.lastModified() }?.map { it.name } ?: emptyList()

    fun frames(name: String, frameBytes: Int): Sequence<ByteArray> = sequence {
        val file = File(directory, name)
        if (!file.canonicalPath.startsWith(directory.canonicalPath) || !file.isFile) return@sequence
        BufferedInputStream(file.inputStream()).use { input ->
            while (true) {
                val frame = ByteArray(frameBytes)
                var read = 0
                while (read < frame.size) {
                    val count = input.read(frame, read, frame.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read != frame.size) break
                yield(frame)
            }
        }
    }
}
