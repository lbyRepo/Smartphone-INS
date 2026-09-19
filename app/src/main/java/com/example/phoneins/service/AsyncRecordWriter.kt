package com.example.phoneins.service

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface RecordSink<T> : Closeable {
    fun write(record: T)
    fun flush()
}

/** One bounded queue per file. The producer never opens, writes or flushes storage. */
internal class AsyncRecordWriter<T>(
    capacity: Int = 1_000,
    open: () -> RecordSink<T>,
) : Closeable {
    private val queue = ArrayBlockingQueue<T>(capacity)
    private val gate = Any()
    private val flushRequested = AtomicBoolean(false)
    private val lost = AtomicLong(0)
    @Volatile private var accepting = true
    @Volatile var error: String? = null
        private set
    val active get() = accepting && error == null
    val droppedRecords get() = lost.get()

    private val worker = Thread({
        var inFlight = false
        try {
            open().use { sink ->
                while (accepting || queue.isNotEmpty()) {
                    val record = queue.poll(50, TimeUnit.MILLISECONDS)
                    if (record != null) {
                        inFlight = true
                        sink.write(record)
                        inFlight = false
                    }
                    if (flushRequested.getAndSet(false)) sink.flush()
                }
                sink.flush()
            }
        } catch (failure: Exception) {
            synchronized(gate) {
                accepting = false
                lost.addAndGet(queue.size.toLong() + if (inFlight) 1L else 0L)
                queue.clear()
                error = "Logging failed: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }
    }, "ins-file-writer").apply { isDaemon = true; start() }

    fun offer(record: T): Boolean = synchronized(gate) {
        if (!accepting) return false
        queue.offer(record).also { if (!it) lost.incrementAndGet() }
    }

    fun flush() { flushRequested.set(true) }

    /** Drains accepted records; bounded wait prevents a stuck disk blocking service shutdown. */
    override fun close() {
        synchronized(gate) { accepting = false }
        worker.join(2_000)
        if (worker.isAlive && error == null) error = "Log still draining; file may not yet be complete"
    }
}
