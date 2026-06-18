package com.example.mysnipeit.data.ballistics

import com.example.mysnipeit.data.models.GpsFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

/**
 * Pure-math tests for [localizeTarget]. All scenarios assume the current
 * [RigGeometry] defaults (compass on fixed base, servo centers at 90°);
 * if a field test flips those constants, update the servo inputs here to
 * keep the geometric intent of each case.
 *
 * Reference: 1° latitude ≈ 111.19 km at the equator-radius approximation
 * used by the localizer, so 1112 m north ≈ 0.01° lat.
 */
class TargetLocalizerTest {

    private val piGps = GpsFrame(
        valid = true,
        fixType = 3,
        numSatellites = 9,
        latitudeDeg = 31.500000,
        longitudeDeg = 34.500000,
        altitudeM = 100.0,
    )

    /** Helper: localize with rig-level servo (90/90) and a given heading. */
    private fun localize(
        heading: Float?,
        servoH: Float? = 90f,
        servoV: Float? = 90f,
        distance: Float? = 1000f,
        gps: GpsFrame? = piGps,
    ) = localizeTarget(gps, heading, servoH, servoV, distance)

    // --- Happy-path geometry -------------------------------------------------

    @Test
    fun `due north, level — target is straight up the latitude axis`() {
        val t = localize(heading = 0f)!!
        // 1000m north ≈ +0.008993° lat; lon and alt unchanged
        assertEquals(31.508993, t.latitudeDeg, 1e-4)
        assertEquals(34.500000, t.longitudeDeg, 1e-6)
        assertEquals(100.0, t.altitudeM, 1e-6)
    }

    @Test
    fun `due east, level — target is along the longitude axis only`() {
        val t = localize(heading = 90f)!!
        assertEquals(31.500000, t.latitudeDeg, 1e-6)
        // 1000m east at lat 31.5 ≈ +0.008993 / cos(31.5°) ≈ +0.010546° lon
        assertEquals(34.510546, t.longitudeDeg, 1e-4)
    }

    @Test
    fun `due south — latitude decreases`() {
        val t = localize(heading = 180f)!!
        assertEquals(31.491007, t.latitudeDeg, 1e-4)
        assertEquals(34.500000, t.longitudeDeg, 1e-6)
    }

    @Test
    fun `servo pan offset adds to heading when compass is base-mounted`() {
        // Heading 0 + pan 135 (= +45 from the 90 center) → bearing 45 (NE).
        val t = localize(heading = 0f, servoH = 135f)!!
        // Both components ≈ 1000/√2 = 707.1m → dLat ≈ +0.006359°
        assertEquals(31.506359, t.latitudeDeg, 1e-4)
        // dLon ≈ 0.006359 / cos(31.5°) ≈ 0.007457
        assertEquals(34.507457, t.longitudeDeg, 1e-4)
    }

    @Test
    fun `bearing wraps past 360`() {
        // Heading 350 + pan +45 → 395 → wraps to 35. Must not throw or
        // produce a negative-bearing projection.
        val t = localize(heading = 350f, servoH = 135f)
        assertNotNull(t)
        // NE-ish quadrant: both lat and lon must increase
        assert(t!!.latitudeDeg > piGps.latitudeDeg)
        assert(t.longitudeDeg > piGps.longitudeDeg)
    }

    @Test
    fun `upward tilt splits slant into shorter horizontal plus altitude gain`() {
        // 30° up (servoV 120), 1000m slant → horizontal 866m, vertical +500m
        val t = localize(heading = 0f, servoV = 120f)!!
        assertEquals(600.0, t.altitudeM, 0.5)             // 100 + 500
        assertEquals(31.507789, t.latitudeDeg, 1e-4)      // 866m north
    }

    @Test
    fun `downward tilt lowers target altitude`() {
        // 30° down (servoV 60): same horizontal, altitude 100 - 500 = -400
        val t = localize(heading = 0f, servoV = 60f)!!
        assertEquals(-400.0, t.altitudeM, 0.5)
    }

    // --- Missing-input guards (must return null, never a garbage solution) ---

    @Test
    fun `null when gps missing or invalid`() {
        assertNull(localize(heading = 0f, gps = null))
        assertNull(localize(heading = 0f, gps = piGps.copy(valid = false)))
    }

    @Test
    fun `null when compass heading missing`() {
        // The "magnetometer not fixed yet" case — heading must never be
        // silently treated as 0° (true north).
        assertNull(localize(heading = null))
    }

    @Test
    fun `null when servo pan missing in base-mounted config`() {
        assertNull(localize(heading = 0f, servoH = null))
    }

    @Test
    fun `null when servo tilt missing`() {
        assertNull(localize(heading = 0f, servoV = null))
    }

    @Test
    fun `null when rangefinder missing or non-positive`() {
        assertNull(localize(heading = 0f, distance = null))
        assertNull(localize(heading = 0f, distance = 0f))
        assertNull(localize(heading = 0f, distance = -5f))
    }

    // --- normalizeDeg --------------------------------------------------------

    @Test
    fun `normalizeDeg wraps both directions`() {
        assertEquals(0.0, normalizeDeg(360.0), 1e-9)
        assertEquals(35.0, normalizeDeg(395.0), 1e-9)
        assertEquals(350.0, normalizeDeg(-10.0), 1e-9)
        assertEquals(0.0, normalizeDeg(-720.0), 1e-9)
    }

    // --- Round-trip sanity ----------------------------------------------------

    @Test
    fun `distance from pi to localized target matches horizontal range`() {
        val t = localize(heading = 37f, servoH = 105f, servoV = 100f, distance = 800f)!!
        // Recompute ground distance with the same projection the localizer
        // uses; must equal slant·cos(10°) within a meter.
        val dLatM = Math.toRadians(t.latitudeDeg - piGps.latitudeDeg) * 6_371_000.0
        val dLonM = Math.toRadians(t.longitudeDeg - piGps.longitudeDeg) *
                6_371_000.0 * Math.cos(Math.toRadians(piGps.latitudeDeg))
        val groundM = Math.sqrt(dLatM * dLatM + dLonM * dLonM)
        val expected = 800.0 * Math.cos(Math.toRadians(10.0))
        assert(abs(groundM - expected) < 1.0) {
            "ground=$groundM expected=$expected"
        }
    }
}
