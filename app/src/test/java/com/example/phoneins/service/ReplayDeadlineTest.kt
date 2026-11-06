package com.example.phoneins.service

import org.junit.Test
import org.junit.Assert.assertEquals

class ReplayDeadlineTest {
    @Test fun processingTimeIsSubtractedAtEverySpeed() {
        for (speed in listOf(0.5, 1.0, 2.0, 10.0)) {
            var now = 0L
            val scheduler = ReplayDeadline { now }
            val period = (10_000_000 / speed).toLong()
            assertEquals(period, scheduler.advance(10_000_000, speed))
            now = period + 100_000
            assertEquals(period - 100_000, scheduler.advance(10_000_000, speed))
            now += 500_000_000
            scheduler.rebase()
            assertEquals(period, scheduler.advance(10_000_000, speed))
        }
    }
}
