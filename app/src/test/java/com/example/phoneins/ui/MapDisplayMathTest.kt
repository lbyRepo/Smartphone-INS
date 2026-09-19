package com.example.phoneins.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.osmdroid.util.GeoPoint

class MapDisplayMathTest {
    @Test fun scaleChoosesSmallerDistanceWhenFiveHundredMetresDoesNotFit() {
        assertEquals(25.0, scaleBarMetres(5f, 1f))
        assertEquals(10.0, scaleBarMetres(10f, 1f))
        assertNull(scaleBarMetres(Float.NaN, 1f))
        assertNull(scaleBarMetres(0f, 1f))
    }

    @Test fun scaleAlwaysFitsTheRequestedPixelRange() {
        for (density in listOf(1f, 2.625f, 3f)) for (resolution in listOf(0.05f, 1f, 5f, 20f, 100f)) {
            val metres = checkNotNull(scaleBarMetres(resolution, density))
            assertTrue(metres * resolution in 48.0 * density..130.0 * density)
        }
    }

    @Test fun fixedLengthTrailsInvalidateGeometryWhenCoordinatesChange() {
        val points = (0 until 2_000).map { GeoPoint(1.0 + it * 0.00001, 103.0) }
        val first = MapBasemapData(points, points, points.first(), 0.0)
        val nextPoints = points.drop(1) + GeoPoint(1.02, 103.0)
        val next = first.copy(insPoints = nextPoints, gnssPoints = nextPoints)
        assertTrue(!sameMapGeometry(first, next))
        assertTrue(sameMapGeometry(first, first.copy(insHeadingDeg = 10.0)))
        assertTrue(first != next)
    }
}
