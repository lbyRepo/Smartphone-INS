package com.example.phoneins.ui

import com.example.phoneins.navigation.FixStatus
import com.example.phoneins.navigation.InitSource
import com.example.phoneins.navigation.MotionProfile
import com.example.phoneins.navigation.NavigationSolution
import com.example.phoneins.navigation.QualityFlag
import com.example.phoneins.navigation.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PositionMapStateTest {

    private fun solution(
        sequence: Long,
        monotonicNanos: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
        initialized: Boolean = true,
    ) = NavigationSolution(
        monotonicNanos = monotonicNanos,
        unixMillis = 0L,
        latitudeDeg = latitudeDeg,
        longitudeDeg = longitudeDeg,
        altitudeEllipsoidM = 0.0,
        velocityNedMps = Vec3.ZERO,
        eulerDeg = Vec3.ZERO,
        positionStdNedM = Vec3.ZERO,
        velocityStdNedMps = Vec3.ZERO,
        attitudeStdDeg = Vec3.ZERO,
        latestImu = null,
        latestGnss = null,
        initializationSource = InitSource.AUTOMATIC,
        motionProfile = MotionProfile.VEHICLE,
        fixStatus = FixStatus.FIX_3D,
        qualityFlags = if (initialized) QualityFlag.INITIALIZED else 0,
        sequence = sequence,
    )

    @Test
    fun firstPointSeedsOriginAndTrail() {
        val map = PositionMapState()
        map.update(solution(1, 0L, 1.0, 2.0))
        assertEquals(1, map.insTrail.size)
        assertEquals(1.0, map.origin?.latitudeDeg ?: Double.NaN, 1e-12)
        assertEquals(2.0, map.origin?.longitudeDeg ?: Double.NaN, 1e-12)
    }

    @Test
    fun appendGatingKeepsShortSlowMovesOut() {
        val map = PositionMapState()
        map.update(solution(1, 0L, 1.0, 2.0))
        // 50 ms and ~0.11 m: below both gates.
        map.update(solution(2, 50_000_000L, 1.000001, 2.0))
        assertEquals(1, map.insTrail.size)
        // 150 ms later but no movement: not appended.
        map.update(solution(3, 200_000_000L, 1.000001, 2.0))
        assertEquals(1, map.insTrail.size)
        // A full second since the last stored point: appended even without movement.
        map.update(solution(4, 1_200_000_000L, 1.0, 2.0))
        assertEquals(2, map.insTrail.size)
        // 200 ms and ~2.2 m: appended.
        map.update(solution(5, 1_400_000_000L, 1.00002, 2.0))
        assertEquals(3, map.insTrail.size)
    }

    @Test
    fun trailIsCappedAtTwoThousandPointsAndKeepsOrigin() {
        val map = PositionMapState()
        var monotonic = 0L
        for (index in 0 until MAX_TRAIL_POINTS + 500) {
            monotonic += 1_000_000_000L
            map.update(solution(index.toLong(), monotonic, 1.0 + index * 1e-5, 2.0))
        }
        assertEquals(MAX_TRAIL_POINTS, map.insTrail.size)
        // Oldest 500 points are dropped, the newest are kept.
        assertEquals(1.0 + 500 * 1e-5, map.insTrail.first().latitudeDeg, 1e-12)
        assertEquals(1.0 + (MAX_TRAIL_POINTS + 499) * 1e-5, map.insTrail.last().latitudeDeg, 1e-12)
        // The origin still marks the initialization location for the displacement readout.
        assertEquals(1.0, map.origin?.latitudeDeg ?: Double.NaN, 1e-12)
    }

    @Test
    fun sequenceResetClearsTrailsAndOrigin() {
        val map = PositionMapState()
        map.update(solution(10, 0L, 1.0, 2.0))
        map.update(solution(11, 2_000_000_000L, 1.001, 2.001))
        assertEquals(2, map.insTrail.size)
        map.update(solution(5, 3_000_000_000L, 1.0, 2.0))
        // The reset clears history, and the reset solution itself re-seeds the trail and origin.
        assertEquals(1, map.insTrail.size)
        assertEquals(1.0, map.origin?.latitudeDeg ?: Double.NaN, 1e-12)
        // Subsequent slow/stationary updates are gated as usual.
        map.update(solution(6, 3_050_000_000L, 1.000001, 2.0))
        assertEquals(1, map.insTrail.size)
    }

    @Test
    fun uninitializedUpdatesAreIgnored() {
        val map = PositionMapState()
        map.update(solution(1, 0L, 1.0, 2.0, initialized = false))
        assertEquals(0, map.insTrail.size)
        assertEquals(0, map.gnssTrail.size)
        assertNull(map.origin)
    }

    @Test
    fun versionAdvancesOnlyWhenTrailContentsChange() {
        val map = PositionMapState()
        val before = map.version
        // Ignored updates on an empty state must not bump the version.
        map.update(solution(1, 0L, 1.0, 2.0, initialized = false))
        assertEquals(before, map.version)
        map.update(solution(1, 0L, 1.0, 2.0))
        assertEquals(before + 1, map.version)
        // Suppressed appends do not bump the version.
        map.update(solution(2, 50_000_000L, 1.000001, 2.0))
        assertEquals(before + 1, map.version)
        // Accepted appends bump the version, and a sequence reset bumps it twice:
        // once for the clear and once for the re-seeding reset point.
        map.update(solution(3, 2_000_000_000L, 1.0, 2.0))
        assertEquals(before + 2, map.version)
        map.update(solution(1, 3_000_000_000L, 1.0, 2.0))
        assertEquals(before + 4, map.version)
        assertEquals(1, map.insTrail.size)
    }
}
