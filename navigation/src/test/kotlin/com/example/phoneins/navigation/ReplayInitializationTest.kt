package com.example.phoneins.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayInitializationTest {
    @Test
    fun recordedStateSeedsReplayNavigationEngine() {
        val time = 10_000_000_000L
        val engine = NavigationEngine()
        engine.initializeReplay(
            ReplayInitialization(
                latitudeDeg = 1.3521,
                longitudeDeg = 103.8198,
                altitudeEllipsoidM = 25.0,
                velocityNedMps = Vec3(2.0, -1.0, 0.2),
                eulerDeg = Vec3(3.0, -2.0, 45.0),
                accelerometerBiasMps2 = Vec3(0.01, -0.02, 0.03),
                gyroBiasRadps = Vec3(0.001, -0.002, 0.003),
                motionProfile = MotionProfile.PEDESTRIAN,
                imuSample = TimedImuSample(time, Vec3(0.0, 0.0, -9.80665), Vec3.ZERO),
            ),
        )

        val solution = engine.snapshot(time, 1_750_000_000_000L)
        assertTrue(solution.initialized)
        assertEquals(InitSource.REPLAY, solution.initializationSource)
        assertEquals(MotionProfile.PEDESTRIAN, solution.motionProfile)
        assertTrue(kotlin.math.abs(solution.latitudeDeg - 1.3521) < 1e-7)
        assertTrue(kotlin.math.abs(solution.longitudeDeg - 103.8198) < 1e-7)
        assertTrue((solution.velocityNedMps - Vec3(2.0, -1.0, 0.2)).norm() < 1e-6)
        assertEquals(Vec3(0.01, -0.02, 0.03), solution.accelerometerBiasMps2)
        assertEquals(Vec3(0.001, -0.002, 0.003), solution.gyroBiasRadps)
    }
}
