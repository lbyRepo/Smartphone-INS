package com.example.phoneins.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.example.phoneins.navigation.*
import java.util.ArrayDeque
import kotlin.math.cos
import kotlin.math.sin

class AndroidAcquisition(
    private val context: Context,
    private val onImu: (TimedImuSample) -> Unit,
    private val onMag: (MagSample) -> Unit,
    private val onGnss: (GnssFix) -> Unit,
    private val onCapability: (String) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val thread = HandlerThread("ins-acquisition")
    private lateinit var handler: Handler
    private val imuPairer = ImuSamplePairer()
    val accelerationTimeNanos get() = imuPairer.accelerationTimeNanos
    val gyroTimeNanos get() = imuPairer.gyroTimeNanos
    private var satellitesUsed = 0
    private val altitudeHistory = ArrayDeque<Pair<Long, Double>>()

    private val locationListener = LocationListener { location -> handleLocation(location) }
    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        thread.start()
        handler = Handler(thread.looper)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        listOfNotNull(accel, gyro, mag).forEach { sensorManager.registerListener(this, it, 5_000, 0, handler) }
        onCapability("IMU: ${accel?.name ?: "missing accel"} · ${gyro?.name ?: "missing gyro"}")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locationListener, thread.looper)
            locationManager.registerGnssStatusCallback(statusCallback, handler)
        } else {
            onCapability("Fine location permission is required for live GNSS")
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        runCatching { locationManager.removeUpdates(locationListener) }
        runCatching { locationManager.unregisterGnssStatusCallback(statusCallback) }
        thread.quitSafely()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val native = Vec3(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        val body = Vec3(native.y, native.x, -native.z)
        val range = event.sensor.maximumRange.toDouble()
        val clipped = range.isFinite() && range > 0 && native.toArray().any { kotlin.math.abs(it) >= 0.98 * range }
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> imuPairer.accelerometer(event.timestamp, body, clipped)
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> imuPairer.gyroscope(event.timestamp, body, clipped)?.let(onImu)
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> onMag(MagSample(event.timestamp, body))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleLocation(location: Location) {
        val time = location.elapsedRealtimeNanos
        if (location.hasAltitude()) {
            altitudeHistory.addLast(time to location.altitude)
            while (altitudeHistory.size > 5) altitudeHistory.removeFirst()
        }
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val speedAccuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else Double.NaN
        val directionKnown = location.hasBearing()
        val stationarySpeed = location.hasSpeed() && speed <= 0.5 && (!speedAccuracy.isFinite() || speedAccuracy <= 1.5)
        val bearing = Math.toRadians(if (directionKnown) location.bearing.toDouble() else 0.0)
        val north = if (directionKnown) speed * cos(bearing) else 0.0
        val east = if (directionKnown) speed * sin(bearing) else 0.0
        val down = if (altitudeHistory.size >= 3) {
            val first = altitudeHistory.first()
            val last = altitudeHistory.last()
            -(last.second - first.second) / ((last.first - first.first) * 1e-9).coerceAtLeast(0.1)
        } else Double.NaN
        onGnss(
            GnssFix(
                monotonicNanos = time,
                unixMillis = location.time,
                latitudeDeg = location.latitude,
                longitudeDeg = location.longitude,
                altitudeEllipsoidM = if (location.hasAltitude()) location.altitude else 0.0,
                velocityNedMps = Vec3(north, east, down),
                horizontalAccuracyM = location.accuracy.toDouble(),
                verticalAccuracyM = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else Double.NaN,
                speedAccuracyMps = speedAccuracy,
                bearingAccuracyDeg = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else Double.NaN,
                satellitesUsed = satellitesUsed,
                hasAltitude = location.hasAltitude(),
                // Bearing is undefined at rest, but a reliable near-zero speed is still a valid
                // zero-velocity observation and must be allowed to bound the INS.
                hasHorizontalVelocity = location.hasSpeed() && (directionKnown || stationarySpeed),
                hasDerivedDownVelocity = down.isFinite(),
                isMock = location.isMock,
            ),
        )
    }
}
