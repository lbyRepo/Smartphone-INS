package com.example.phoneins.navigation

import kotlin.math.*

internal const val WGS84_A = 6_378_137.0
internal const val WGS84_E2 = 6.69437999014e-3

/** Skew of ECEF specific force for a left-multiplicative ECEF attitude error. */
internal fun ecefSpecificForceSkew(bodyToEcef: DoubleArray, forceBody: Vec3): DoubleArray {
    val f = multiply3(bodyToEcef, forceBody)
    return doubleArrayOf(0.0, -f.z, f.y, f.z, 0.0, -f.x, -f.y, f.x, 0.0)
}

/** sqrt(diag(J P J^T)), including off-diagonal covariance terms. */
internal fun projectedStandardDeviation(p: DoubleArray, size: Int, offset: Int, j: DoubleArray): Vec3 =
    Vec3.from(DoubleArray(3) { axis ->
        var variance = 0.0
        for (r in 0..2) for (c in 0..2) {
            variance += j[axis * 3 + r] * p[(offset + r) * size + offset + c] * j[axis * 3 + c]
        }
        sqrt(variance.coerceAtLeast(0.0))
    })

/** Maps a small left NED rotation into roll/pitch/yaw perturbations.
 * Euler uncertainty grows near gimbal lock; cap the singular denominator, not the uncertainty.
 */
internal fun nedRotationToEulerJacobian(pitch: Double, yaw: Double): DoubleArray {
    val cp = cos(pitch).coerceAtLeast(1e-6)
    val cy = cos(yaw)
    val sy = sin(yaw)
    return doubleArrayOf(cy / cp, sy / cp, 0.0, -sy, cy, 0.0, cy * sin(pitch) / cp, sy * sin(pitch) / cp, 1.0)
}

data class Geodetic(val latitudeRad: Double, val longitudeRad: Double, val altitudeM: Double)

data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(other: Quaternion) = Quaternion(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n > 1e-12) Quaternion(w / n, x / n, y / n, z / n) else IDENTITY
    }

    fun toMatrix(): DoubleArray {
        val q = normalized()
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z
        val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        return doubleArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
        )
    }

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        fun fromRotationVector(vector: Vec3): Quaternion {
            val angle = vector.norm()
            if (angle < 1e-10) return Quaternion(1.0, vector.x / 2, vector.y / 2, vector.z / 2).normalized()
            val scale = sin(angle / 2) / angle
            return Quaternion(cos(angle / 2), vector.x * scale, vector.y * scale, vector.z * scale)
        }

        fun fromMatrix(m: DoubleArray): Quaternion {
            val trace = m[0] + m[4] + m[8]
            val q = when {
                trace > 0 -> {
                    val s = sqrt(trace + 1.0) * 2
                    Quaternion(0.25 * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
                }
                m[0] > m[4] && m[0] > m[8] -> {
                    val s = sqrt(1 + m[0] - m[4] - m[8]) * 2
                    Quaternion((m[7] - m[5]) / s, 0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
                }
                m[4] > m[8] -> {
                    val s = sqrt(1 + m[4] - m[0] - m[8]) * 2
                    Quaternion((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s)
                }
                else -> {
                    val s = sqrt(1 + m[8] - m[0] - m[4]) * 2
                    Quaternion((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s)
                }
            }
            return q.normalized()
        }
    }
}

internal fun geodeticToEcef(latitudeDeg: Double, longitudeDeg: Double, altitudeM: Double): Vec3 {
    val lat = Math.toRadians(latitudeDeg)
    val lon = Math.toRadians(longitudeDeg)
    val sinLat = sin(lat)
    val n = WGS84_A / sqrt(1 - WGS84_E2 * sinLat * sinLat)
    return Vec3(
        (n + altitudeM) * cos(lat) * cos(lon),
        (n + altitudeM) * cos(lat) * sin(lon),
        (n * (1 - WGS84_E2) + altitudeM) * sinLat,
    )
}

internal fun ecefToGeodetic(ecef: Vec3): Geodetic {
    val lon = atan2(ecef.y, ecef.x)
    val p = hypot(ecef.x, ecef.y)
    var lat = atan2(ecef.z, p * (1 - WGS84_E2))
    var altitude = 0.0
    repeat(8) {
        val sinLat = sin(lat)
        val n = WGS84_A / sqrt(1 - WGS84_E2 * sinLat * sinLat)
        altitude = p / cos(lat) - n
        lat = atan2(ecef.z, p * (1 - WGS84_E2 * n / (n + altitude)))
    }
    return Geodetic(lat, lon, altitude)
}

internal fun ecefToNedMatrix(latitudeRad: Double, longitudeRad: Double): DoubleArray {
    val sl = sin(latitudeRad); val cl = cos(latitudeRad)
    val so = sin(longitudeRad); val co = cos(longitudeRad)
    return doubleArrayOf(
        -sl * co, -sl * so, cl,
        -so, co, 0.0,
        -cl * co, -cl * so, -sl,
    )
}

internal fun transpose3(m: DoubleArray) = doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

internal fun multiply3(a: DoubleArray, b: DoubleArray): DoubleArray {
    val out = DoubleArray(9)
    for (r in 0..2) for (c in 0..2) for (k in 0..2) out[r * 3 + c] += a[r * 3 + k] * b[k * 3 + c]
    return out
}

internal fun multiply3(m: DoubleArray, v: Vec3) = Vec3(
    m[0] * v.x + m[1] * v.y + m[2] * v.z,
    m[3] * v.x + m[4] * v.y + m[5] * v.z,
    m[6] * v.x + m[7] * v.y + m[8] * v.z,
)

internal fun bodyToNedMatrix(rollRad: Double, pitchRad: Double, yawRad: Double): DoubleArray {
    val cr = cos(rollRad); val sr = sin(rollRad)
    val cp = cos(pitchRad); val sp = sin(pitchRad)
    val cy = cos(yawRad); val sy = sin(yawRad)
    return doubleArrayOf(
        cp * cy, sr * sp * cy - cr * sy, cr * sp * cy + sr * sy,
        cp * sy, sr * sp * sy + cr * cy, cr * sp * sy - sr * cy,
        -sp, sr * cp, cr * cp,
    )
}

internal fun eulerFromBodyToNed(r: DoubleArray): Vec3 {
    val pitch = asin((-r[6]).coerceIn(-1.0, 1.0))
    val roll = atan2(r[7], r[8])
    var yaw = atan2(r[3], r[0])
    if (yaw < 0) yaw += 2 * Math.PI
    return Vec3(Math.toDegrees(roll), Math.toDegrees(pitch), Math.toDegrees(yaw))
}

internal fun gravityEcef(position: Vec3): Vec3 {
    val radius = position.norm().coerceAtLeast(WGS84_A / 2)
    val gravity = 9.7803253359 * (WGS84_A * WGS84_A) / (radius * radius)
    return position.normalized() * -gravity
}

internal fun wrapRadians(value: Double): Double {
    var result = value
    while (result > Math.PI) result -= 2 * Math.PI
    while (result < -Math.PI) result += 2 * Math.PI
    return result
}
