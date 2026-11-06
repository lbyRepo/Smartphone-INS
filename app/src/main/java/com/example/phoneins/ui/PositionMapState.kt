package com.example.phoneins.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.phoneins.navigation.NavigationSolution
import com.example.phoneins.navigation.Vec3
import kotlin.math.cos
import kotlin.math.hypot

internal data class TrailPoint(val monotonicNanos: Long, val latitudeDeg: Double, val longitudeDeg: Double)

/** Upper bound on plotted history per trail; longer sessions drop the oldest points. */
internal const val MAX_TRAIL_POINTS = 2_000

internal class PositionMapState {
    val insTrail = mutableStateListOf<TrailPoint>()
    val gnssTrail = mutableStateListOf<TrailPoint>()
    var origin by mutableStateOf<TrailPoint?>(null)
        private set

    /** Bumped whenever plotted trail contents change, so the UI re-derives snapshots only on change. */
    var version by mutableLongStateOf(0L)
        private set
    private var lastSequence: Long? = null

    internal fun update(solution: NavigationSolution) {
        val sequenceReset = lastSequence?.let { solution.sequence < it } == true
        if (!solution.initialized || sequenceReset) {
            clear()
            if (!solution.initialized) return
        }
        val insValid = solution.latitudeDeg.isFinite() && solution.longitudeDeg.isFinite()
        if (insValid) {
            val point = TrailPoint(solution.monotonicNanos, solution.latitudeDeg, solution.longitudeDeg)
            if (origin == null) origin = point
            appendTrail(insTrail, point)
        }
        solution.latestGnss?.takeIf { it.latitudeDeg.isFinite() && it.longitudeDeg.isFinite() }?.let {
            appendTrail(gnssTrail, TrailPoint(it.monotonicNanos, it.latitudeDeg, it.longitudeDeg))
        }
        lastSequence = solution.sequence
    }

    private fun appendTrail(trail: MutableList<TrailPoint>, point: TrailPoint) {
        val previous = trail.lastOrNull()
        val elapsed = previous?.let { point.monotonicNanos - it.monotonicNanos } ?: Long.MAX_VALUE
        val distance = previous?.let { point.toLocalMetres(it).let { local -> hypot(local.x, local.y) } } ?: Double.POSITIVE_INFINITY
        if (previous == null || elapsed >= 1_000_000_000L || (elapsed >= 100_000_000L && distance >= 1.0)) {
            trail += point
            while (trail.size > MAX_TRAIL_POINTS) trail.removeAt(0)
            version++
        }
    }

    private fun clear() {
        val hadContent = insTrail.isNotEmpty() || gnssTrail.isNotEmpty() || origin != null
        insTrail.clear()
        gnssTrail.clear()
        origin = null
        lastSequence = null
        if (hadContent) version++
    }
}

internal fun TrailPoint.toLocalMetres(origin: TrailPoint): Vec3 {
    val north = (latitudeDeg - origin.latitudeDeg) * 111_132.0
    val east = (longitudeDeg - origin.longitudeDeg) * 111_320.0 * cos(Math.toRadians(origin.latitudeDeg))
    return Vec3(north, east, 0.0)
}

internal fun TrailPoint.toLocalMetresForTest(origin: TrailPoint): Vec3 = toLocalMetres(origin)
