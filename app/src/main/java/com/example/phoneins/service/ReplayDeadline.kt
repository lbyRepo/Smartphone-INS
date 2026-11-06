package com.example.phoneins.service

/** Recorded-time deadlines retain sub-millisecond fractions and exclude processing latency. */
internal class ReplayDeadline(private val now: () -> Long) {
    private var deadline = now().toDouble()
    fun rebase() { deadline = now().toDouble() }
    fun advance(deltaNanos: Long, speed: Double): Long {
        deadline += deltaNanos / speed
        return (deadline - now()).toLong().coerceAtLeast(0L)
    }
}
