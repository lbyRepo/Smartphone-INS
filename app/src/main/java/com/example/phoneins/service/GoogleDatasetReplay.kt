package com.example.phoneins.service

import android.content.Context
import com.example.phoneins.navigation.DecimeterRouteScenario
import com.example.phoneins.navigation.MagSample
import com.example.phoneins.navigation.ManualInitialization
import com.example.phoneins.navigation.TimedImuSample
import com.example.phoneins.navigation.Vec3
import kotlinx.coroutines.delay

/** Replays the bundled 199-second Pixel 4 reference route. GNSS aiding is controlled by the service. */
class GoogleDatasetReplay(private val context: Context) {
    suspend fun run(
        initialize: (ManualInitialization) -> Unit,
        onImu: (TimedImuSample) -> Unit,
        onMag: (MagSample) -> Unit,
        onGnss: (com.example.phoneins.navigation.GnssFix) -> Unit,
    ) {
        val route = context.assets.open("google_decimeter_2021/Pixel4_ground_truth.csv")
            .bufferedReader()
            .useLines { DecimeterRouteScenario.parseGroundTruth(it) }
        require(route.size >= 2) { "Pixel 4 route fixture is incomplete" }
        val first = route.first()
        val replayStart = android.os.SystemClock.elapsedRealtimeNanos()
        initialize(ManualInitialization(first.latitudeDeg, first.longitudeDeg, first.altitudeM, 0.0, 0.0, first.courseDeg))
        onMag(MagSample(replayStart, Vec3(28.0, -5.0, 36.0)))
        onGnss(DecimeterRouteScenario.gnssFix(first, first, replayStart))

        for (index in 1 until route.size) {
            DecimeterRouteScenario.imuBetween(route[index - 1], route[index], first, replayStart).forEach(onImu)
            if (index % 10 == 0) {
                onMag(MagSample(DecimeterRouteScenario.monotonicNanos(route[index], first, replayStart), Vec3(28.0, -5.0, 36.0)))
            }
            onGnss(DecimeterRouteScenario.gnssFix(route[index], first, replayStart))
            delay(40)
        }
    }
}
