package com.example.phoneins.protocol

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Production byte-stream transport used by the Android foreground service. */
class TcpInsServer(private val requestedPort: Int = 29_500) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(512)
    private val dropCounter = AtomicLong(0)
    private var server: ServerSocket? = null
    private var client: Socket? = null
    @Volatile var boundPort: Int = requestedPort
        private set
    val connected get() = client?.isConnected == true && client?.isClosed == false
    val dropped get() = dropCounter.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            runCatching {
                ServerSocket(requestedPort).also { server = it; boundPort = it.localPort }.use { listening ->
                    while (running.get()) {
                        val socket = listening.accept().also { client = it }
                        socket.tcpNoDelay = true
                        runCatching {
                            socket.getOutputStream().buffered(64 * 1024).use { output ->
                                while (running.get() && !socket.isClosed) {
                                    output.write(queue.take())
                                    output.flush()
                                }
                            }
                        }
                        client = null
                    }
                }
            }
        }, "ins-tcp-server").apply { isDaemon = true; start() }
    }

    fun offer(frame: ByteArray) {
        if (connected && !queue.offer(frame.copyOf())) dropCounter.incrementAndGet()
    }

    override fun close() {
        running.set(false)
        runCatching { client?.close() }
        runCatching { server?.close() }
        queue.clear()
    }
}
