package com.example.phoneins.ui

/** Choose a readable scale within BOTH limits, trying smaller distances when necessary. */
internal fun scaleBarMetres(pixelsPerMetre: Float, density: Float): Double? {
    if (!pixelsPerMetre.isFinite() || pixelsPerMetre <= 0f || density <= 0f) return null
    return listOf(10_000.0, 5_000.0, 2_000.0, 1_000.0, 500.0, 250.0, 200.0, 100.0,
        50.0, 25.0, 20.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.2, 0.1)
        .firstOrNull { it * pixelsPerMetre in (48.0 * density)..(130.0 * density) }
}
