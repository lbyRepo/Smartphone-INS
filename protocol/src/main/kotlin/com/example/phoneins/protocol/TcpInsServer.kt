package com.example.phoneins.protocol

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** Production byte-stream transport used by the Android foreground service. */
class TcpInsServer(private val requestedPort: Int = 29_500) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(512)
    private val dropCounter = AtomicLong(0)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private var worker: Thread? = null
    @Volatile var error: String? = null
        private set
    @Volatile var boundPort: Int = requestedPort
        private set
    val connected get() = client?.isConnected == true && client?.isClosed == false
    val dropped get() = dropCounter.get()

    @Synchronized fun start() {
        if (!running.compareAndSet(false, true)) return
        error = null
        boundPort = requestedPort
        worker = Thread({
            runCatching {
                ServerSocket(requestedPort).also { server = it; boundPort = it.localPort }.use { listening ->
                    while (running.get()) {
                        val socket = listening.accept()
                        synchronized(queue) { discardQueued(); client = socket }
                        socket.tcpNoDelay = true
                        runCatching {
                            socket.getOutputStream().buffered(64 * 1024).use { output ->
                                while (running.get() && !socket.isClosed) {
                                    val frame = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                                    output.write(frame)
                                    output.flush()
                                }
                            }
                        }
                        synchronized(queue) { client = null; discardQueued() }
                    }
                }
            }.onFailure { if (running.get()) error = "TCP output failed: ${it.message}" }
            running.set(false)
        }, "ins-tcp-server").apply { isDaemon = true; start() }
    }

    fun offer(frame: ByteArray) {
        synchronized(queue) {
            if (running.get() && connected && !queue.offer(frame.copyOf())) dropCounter.incrementAndGet()
        }
    }

    @Synchronized override fun close() {
        running.set(false)
        runCatching { client?.close() }
        runCatching { server?.close() }
        worker?.interrupt()
        worker?.join(1_000)
        synchronized(queue) { client = null; discardQueued() }
    }

    private fun discardQueued() { dropCounter.addAndGet(queue.size.toLong()); queue.clear() }
}
