package com.example.mysnipeit.data.ballistics

import com.example.mysnipeit.data.models.GpsFrame
import kotlin.math.cos
import kotlin.math.sin

/**
 * A target's position in world coordinates, as computed by [localizeTarget]
 * from the Pi rig's sensors. This is the hand-off between the two halves of
 * the ballistic calculator:
 *
 *   Pi sensors ──localizeTarget──► TargetLocation ──firing solution──► AZ/EL from the SNIPER
 *
 * The sniper (tablet) and the Pi rig sit at DIFFERENT physical positions,
 * so the firing solution can't reuse the Pi's bearing/distance directly —
 * it recomputes both from the sniper's own GPS toward this location.
 */
data class TargetLocation(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double,
)

/**
 * Rig-geometry assumptions — UNVERIFIED against the physical rig (the
 * mounting details weren't known at the time of writing). Each constant is
 * independently correctable after one field test; nothing else in the code
 * depends on the actual values.
 */
object RigGeometry {
    /**
     * Where the magnetometer is mounted:
     *  - true  = on the FIXED BASE: it doesn't rotate with the pan servo, so
     *    the camera's bearing = compass heading + (servo pan − center).
     *  - false = on the MOVING HEAD: the compass already points where the
     *    camera looks, so the servo pan angle is ignored for bearing.
     */
    const val COMPASS_ON_FIXED_BASE = true

    /** Servo pan angle (deg) at which the camera faces the compass's forward axis. */
    const val SERVO_HORIZONTAL_CENTER_DEG = 90.0

    /**
     * Servo tilt angle (deg) at which the camera looks at the horizon.
     * Values above this tilt UP, below tilt DOWN (standard hobby-servo
     * convention where 90 = centered in a 0–180 range).
     */
    const val SERVO_VERTICAL_LEVEL_DEG = 90.0

    /**
     * Magnetic declination (deg, east-positive) added to the compass heading
     * to get a true-north bearing. 0 is acceptable for the POC (declination
     * in the deployment region is ~5°E ≈ 9m lateral error at 100m); a real
     * deployment would compute it from GPS + date via a WMM table.
     */
    const val MAGNETIC_DECLINATION_DEG = 0.0

    /**
     * Angular offset (deg, clockwise positive) between the compass's
     * north-reference axis and the mic array's azimuth-zero axis. Both
     * are bolted to the fixed tripod so this is a one-time mechanical
     * constant — set when the array is mounted, never changes at
     * runtime.
     *
     * Stays at 0.0 by **design intent**: the mic array is mechanically
     * aligned so that its 0° axis is parallel to the compass's north
     * reference. Field test of the assembled rig may reveal a small
     * mounting error (a few degrees) — measure once and update this
     * constant, no code changes anywhere else.
     */
    const val MIC_ARRAY_OFFSET_DEG = 0.0

    /**
     * Angular offset (deg) between the mic array's azimuth-zero axis
     * and the pan servo's center (90°) position. The mic array and the
     * servo are both bolted to the fixed tripod with mic 0° aligned to
     * servo 90° — so the conversion is:
     *
     *   servo_horizontal_deg = mic_azim_deg + MIC_TO_SERVO_OFFSET_DEG
     *
     * Used by the SET_SERVO_ANGLES slew path (see
     * [com.example.mysnipeit.data.repository.SlewCommandMode]).
     * Independent of [MIC_ARRAY_OFFSET_DEG]: this one is about the
     * mic↔servo relationship (no compass), that one is about the
     * compass↔mic relationship (used for display).
     */
    const val MIC_TO_SERVO_OFFSET_DEG = 90.0
}

/** Mean Earth radius (m) — fine for the equirectangular projection below. */
private const val EARTH_RADIUS_M = 6_371_000.0

/**
 * Compute the target's world coordinates from the Pi rig's sensors.
 *
 * Geometry, step by step:
 *  1. True-north azimuth of the camera/rangefinder boresight:
 *       compass heading (+ declination) [+ servo pan offset if the compass
 *       is base-mounted — see [RigGeometry.COMPASS_ON_FIXED_BASE]].
 *  2. Elevation angle from the servo tilt (0 = horizon).
 *  3. Split the rangefinder's slant distance:
 *       horizontal = slant · cos(elevation),  vertical = slant · sin(elevation).
 *  4. Project the horizontal distance from the Pi's GPS along the azimuth
 *     with an equirectangular (flat-earth) approximation — error is
 *     sub-centimeter at the <5 km ranges a rangefinder can produce, so a
 *     full Vincenty solution would be complexity without benefit.
 *  5. Target altitude = Pi altitude + vertical offset.
 *
 * Returns null when any required input is missing — caller shows "—"
 * rather than a solution computed from garbage. Servo pan may be null only
 * in the head-mounted-compass configuration (where it isn't needed).
 *
 * All angle inputs are degrees; distance in meters.
 */
fun localizeTarget(
    piGps: GpsFrame?,
    compassHeadingDeg: Float?,
    servoHorizontalDeg: Float?,
    servoVerticalDeg: Float?,
    rangefinderDistanceM: Float?,
): TargetLocation? {
    if (piGps == null || !piGps.valid) return null
    if (compassHeadingDeg == null) return null
    if (servoVerticalDeg == null) return null
    if (rangefinderDistanceM == null || rangefinderDistanceM <= 0f) return null

    // 1. Boresight azimuth (true north, 0–360)
    var azimuthDeg = compassHeadingDeg.toDouble() + RigGeometry.MAGNETIC_DECLINATION_DEG
    if (RigGeometry.COMPASS_ON_FIXED_BASE) {
        if (servoHorizontalDeg == null) return null
        azimuthDeg += servoHorizontalDeg.toDouble() - RigGeometry.SERVO_HORIZONTAL_CENTER_DEG
    }
    azimuthDeg = normalizeDeg(azimuthDeg)

    // 2. Elevation angle (0 = horizon, + up)
    val elevationDeg = servoVerticalDeg.toDouble() - RigGeometry.SERVO_VERTICAL_LEVEL_DEG

    // 3. Slant → horizontal + vertical components
    val elevationRad = Math.toRadians(elevationDeg)
    val slantM = rangefinderDistanceM.toDouble()
    val horizontalM = slantM * cos(elevationRad)
    val verticalM = slantM * sin(elevationRad)

    // 4. Project from the Pi's position along the azimuth
    val azimuthRad = Math.toRadians(azimuthDeg)
    val northM = horizontalM * cos(azimuthRad)
    val eastM = horizontalM * sin(azimuthRad)

    val piLatRad = Math.toRadians(piGps.latitudeDeg)
    val dLatDeg = Math.toDegrees(northM / EARTH_RADIUS_M)
    val dLonDeg = Math.toDegrees(eastM / (EARTH_RADIUS_M * cos(piLatRad)))

    return TargetLocation(
        latitudeDeg = piGps.latitudeDeg + dLatDeg,
        longitudeDeg = piGps.longitudeDeg + dLonDeg,
        altitudeM = piGps.altitudeM + verticalM,
    )
}

/** Wrap any angle to [0, 360). */
internal fun normalizeDeg(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
