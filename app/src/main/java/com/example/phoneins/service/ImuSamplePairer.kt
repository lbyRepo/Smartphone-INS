package com.example.phoneins.service

import com.example.phoneins.navigation.TimedImuSample
import com.example.phoneins.navigation.Vec3

/** Causal zero-order hold, limited to 20 ms (four requested 200 Hz samples). */
internal class ImuSamplePairer(private val maximumAgeNanos: Long = 20_000_000L) {
    private var acceleration = Vec3.ZERO
    @Volatile var accelerationTimeNanos: Long? = null
        private set
    @Volatile var gyroTimeNanos: Long? = null
        private set

    fun accelerometer(time: Long, value: Vec3) {
        if (time <= (accelerationTimeNanos ?: Long.MIN_VALUE) || !value.toArray().all { it.isFinite() }) return
        acceleration = value
        accelerationTimeNanos = time
    }

    fun gyroscope(time: Long, value: Vec3): TimedImuSample? {
        if (time <= (gyroTimeNanos ?: Long.MIN_VALUE)) return null
        gyroTimeNanos = time
        val age = accelerationTimeNanos?.let { time - it }
        return TimedImuSample(time, acceleration, value,
            fresh = age != null && age in 0..maximumAgeNanos && value.toArray().all { it.isFinite() })
    }
}
