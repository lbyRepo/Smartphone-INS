package com.example.phoneins.navigation

object MasterReferenceFlag {
    const val POSITION_VALID = 1 shl 0
    const val VELOCITY_VALID = 1 shl 1
    const val ATTITUDE_VALID = 1 shl 2
    const val ANGULAR_RATE_VALID = 1 shl 3
    const val UNCERTAINTY_VALID = 1 shl 4
}

/** One master-INS reference epoch received over the master reference protocol. */
data class MasterInsReference(
    val sequence: Long,
    val masterMonotonicNanos: Long,
    val masterUtcNanos: Long,
    val phoneMonotonicNanos: Long,
    val flags: Int,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double,
    val velocityNedMps: Vec3,
    val bodyToNed: Quaternion,
    val angularRateBodyRadps: Vec3,
    val velocityStdNedMps: Vec3,
    val attitudeStdDeg: Vec3,
    val timeUncertaintyNanos: Long,
) {
    val requiredFieldsValid: Boolean get() = flags and REQUIRED_FLAGS == REQUIRED_FLAGS

    companion object {
        const val REQUIRED_FLAGS = MasterReferenceFlag.VELOCITY_VALID or MasterReferenceFlag.ATTITUDE_VALID or
            MasterReferenceFlag.ANGULAR_RATE_VALID or MasterReferenceFlag.UNCERTAINTY_VALID
    }
}
