package com.example.phoneins.service

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncRecordWriterTest {
    @Test fun slowStorageDoesNotBlockProducerAndOverflowIsCounted() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val records = mutableListOf<Int>()
        val writer = AsyncRecordWriter<Int>(capacity = 2) {
            object : RecordSink<Int> {
                override fun write(record: Int) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    records.add(record)
                }
                override fun flush() = Unit
                override fun close() = Unit
            }
        }
        try {
            assertTrue(writer.offer(1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(writer.offer(2))
            assertTrue(writer.offer(3))
            assertFalse(writer.offer(4))
            assertEquals(1L, writer.droppedRecords)
        } finally {
            release.countDown()
            writer.close()
        }
        assertEquals(listOf(1, 2, 3), records)
        assertFalse(writer.offer(5))
        assertNull(writer.error)
    }

    @Test fun diskFailureIsReportedWithoutEscapingToProducer() {
        val writer = AsyncRecordWriter<Int> {
            object : RecordSink<Int> {
                override fun write(record: Int) { throw IOException("Disk full") }
                override fun flush() = Unit
                override fun close() = Unit
            }
        }
        writer.offer(1)
        writer.close()
        assertFalse(writer.active)
        assertTrue(writer.error!!.contains("Disk full"))
        assertEquals(1L, writer.droppedRecords)
        assertFalse(writer.offer(2))
    }

    @Test fun openAndFlushFailuresAreContained() {
        val openFailure = AsyncRecordWriter<Int> { throw IOException("Permission denied") }
        openFailure.close()
        assertTrue(openFailure.error!!.contains("Permission denied"))
        val flushFailure = AsyncRecordWriter<Int> {
            object : RecordSink<Int> {
                override fun write(record: Int) = Unit
                override fun flush() { throw IOException("Flush failed") }
                override fun close() = Unit
            }
        }
        flushFailure.flush()
        flushFailure.close()
        assertTrue(flushFailure.error!!.contains("Flush failed"))
    }
}
