package com.example.phoneins.service

import android.os.SystemClock
import com.example.phoneins.navigation.MasterInsReference
import com.example.phoneins.protocol.MasterClockSynchronizer
import com.example.phoneins.protocol.MasterInsProtocol
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class MasterLinkStatus(
    val connected: Boolean = false,
    val rateHz: Double = 0.0,
    val message: String = "Waiting for master INS",
)

/** Bidirectional MST1 server. Output INS1 remains on port 29500. */
class MasterReferenceServer(
    private val port: Int = PORT,
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val onReference: (MasterInsReference, MasterLinkStatus) -> Unit,
    private val onStatus: (MasterLinkStatus) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private var worker: Thread? = null
    @Volatile var listeningPort: Int = -1
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "mst1-reference-server", isDaemon = true) {
            runCatching {
                ServerSocket(port).also { server = it; listeningPort = it.localPort }.use { listening ->
                    listening.reuseAddress = true
                    while (running.get()) {
                        runCatching { listening.accept().also { client = it; serve(it) } }
                        client = null
                        if (running.get()) onStatus(MasterLinkStatus(message = "Master disconnected · navigation continues"))
                    }
                }
            }.onFailure { if (running.get()) onStatus(MasterLinkStatus(message = "Master server error: ${it.message}")) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            socket.tcpNoDelay = true
            socket.soTimeout = 0
            val input = socket.getInputStream().buffered(16 * 1024)
            val output = socket.getOutputStream().buffered(4 * 1024)
            val synchronizer = MasterClockSynchronizer()
            val referenceTimes = ArrayDeque<Long>()
            var lastMasterSequence = -1L
            var lastMasterTime = Long.MIN_VALUE
            onStatus(MasterLinkStatus(connected = true, message = "Master connected · synchronizing clocks"))
            val syncRunning = AtomicBoolean(true)
            val syncThread = thread(name = "mst1-clock-sync", isDaemon = true) {
                var syncSequence = 0L
                while (running.get() && syncRunning.get() && !socket.isClosed) {
                    val sent = nowNanos()
                    runCatching {
                        synchronized(output) {
                            output.write(MasterInsProtocol.encodeSyncRequest(syncSequence++, sent))
                            output.flush()
                        }
                    }.onFailure { return@thread }
                    Thread.sleep(1_000L)
                }
            }
            while (running.get() && !socket.isClosed) {
                val frame = readFrame(input)
                val receiveTime = nowNanos()
                when (val message = MasterInsProtocol.decode(frame)) {
                    is MasterInsProtocol.Message.SyncResponse -> {
                        synchronizer.observe(
                            message.phoneSendNanos, message.masterReceiveNanos,
                            message.masterSendNanos, receiveTime,
                        )
                        onStatus(
                            MasterLinkStatus(
                                connected = true,
                                message = if (synchronizer.uncertaintyNanos < MAX_CLOCK_UNCERTAINTY_NANOS) "Master synchronized" else "Clock uncertainty exceeds 10 ms",
                            ),
                        )
                    }
                    is MasterInsProtocol.Message.Reference -> {
                        val raw = message.value
                        if (raw.sequence <= lastMasterSequence || raw.masterMonotonicNanos <= lastMasterTime) continue
                        lastMasterSequence = raw.sequence
                        lastMasterTime = raw.masterMonotonicNanos
                        if (synchronizer.uncertaintyNanos >= MAX_CLOCK_UNCERTAINTY_NANOS) continue
                        val mapped = synchronizer.masterToPhone(raw.masterMonotonicNanos)
                        val age = receiveTime - mapped
                        if (age !in 0L..MAX_MASTER_AGE_NANOS || !raw.finite()) continue
                        referenceTimes.addLast(receiveTime)
                        while (referenceTimes.size > 1 && receiveTime - referenceTimes.first() > RATE_WINDOW_NANOS) referenceTimes.removeFirst()
                        val duration = if (referenceTimes.size > 1) (referenceTimes.last() - referenceTimes.first()) * 1e-9 else 0.0
                        val rate = if (duration > 0.0) (referenceTimes.size - 1) / duration else 0.0
                        val status = MasterLinkStatus(
                            connected = true,
                            rateHz = rate,
                            message = if (duration >= 1.0 && rate < MIN_MASTER_RATE_HZ) "Master rate below 20 Hz" else "Master reference valid",
                        )
                        onStatus(status)
                        if (duration >= 1.0 && rate >= MIN_MASTER_RATE_HZ) {
                            onReference(raw.copy(phoneMonotonicNanos = mapped, timeUncertaintyNanos = synchronizer.uncertaintyNanos), status)
                        }
                    }
                    is MasterInsProtocol.Message.SyncRequest -> Unit
                }
            }
            syncRunning.set(false)
            syncThread.interrupt()
            syncThread.join(250L)
        }
    }

    private fun readFrame(input: java.io.BufferedInputStream): ByteArray {
        val header = ByteArray(MasterInsProtocol.HEADER_BYTES)
        var offset = 0
        while (offset < header.size) {
            val count = input.read(header, offset, header.size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
        val total = MasterInsProtocol.frameLength(header)
        val frame = ByteArray(total)
        header.copyInto(frame)
        offset = header.size
        while (offset < total) {
            val count = input.read(frame, offset, total - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
        return frame
    }

    override fun close() {
        running.set(false)
        runCatching { client?.close() }
        runCatching { server?.close() }
        worker?.join(500)
    }

    private fun MasterInsReference.finite(): Boolean =
        requiredFieldsValid && listOf(
            latitudeDeg, longitudeDeg, altitudeM,
            velocityNedMps.x, velocityNedMps.y, velocityNedMps.z,
            bodyToNed.w, bodyToNed.x, bodyToNed.y, bodyToNed.z,
            angularRateBodyRadps.x, angularRateBodyRadps.y, angularRateBodyRadps.z,
        ).all(Double::isFinite)

    companion object {
        const val PORT = 29501
        const val MIN_MASTER_RATE_HZ = 20.0
        const val MAX_CLOCK_UNCERTAINTY_NANOS = 10_000_000L
        const val MAX_MASTER_AGE_NANOS = 100_000_000L
        private const val RATE_WINDOW_NANOS = 2_000_000_000L
    }
}
