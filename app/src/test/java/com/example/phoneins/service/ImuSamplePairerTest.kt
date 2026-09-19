package com.example.phoneins.service

import com.example.phoneins.navigation.Vec3
import org.junit.Assert.*
import org.junit.Test

class ImuSamplePairerTest {
    @Test fun missingStaleFutureAndOutOfOrderSamplesAreNotFresh() {
        val pairer = ImuSamplePairer()
        assertFalse(pairer.gyroscope(1L, Vec3.ZERO)!!.fresh)
        pairer.accelerometer(2L, Vec3(0.0, 0.0, -9.81))
        assertTrue(pairer.gyroscope(3L, Vec3.ZERO)!!.fresh)
        assertFalse(pairer.gyroscope(30_000_000L, Vec3.ZERO)!!.fresh)
        assertNull(pairer.gyroscope(29_000_000L, Vec3.ZERO))
        pairer.accelerometer(50_000_000L, Vec3.ZERO)
        assertFalse(pairer.gyroscope(40_000_000L, Vec3.ZERO)!!.fresh)
        assertTrue(pairer.gyroscope(51_000_000L, Vec3.ZERO)!!.fresh)
        pairer.accelerometer(49_000_000L, Vec3(1.0, 0.0, 0.0))
        assertEquals(Vec3.ZERO, pairer.gyroscope(52_000_000L, Vec3.ZERO)!!.accelerationMps2)
    }
}
