package com.example.phoneins.service

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SessionRecorder(context: Context) {
    private val directory = File(context.filesDir, "sessions").apply { mkdirs() }
    private var output: BufferedOutputStream? = null
    val recording get() = output != null

    fun start(): String {
        stop()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".inslog"
        output = File(directory, name).outputStream().buffered(64 * 1024)
        return name
    }

    fun append(frame: ByteArray) { output?.write(frame) }
    fun flush() { output?.flush() }
    fun stop() { runCatching { output?.flush() }; runCatching { output?.close() }; output = null }
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
