package com.example.mysnipeit.data.ballistics

import com.example.mysnipeit.data.models.AcousticEvent

/**
 * Convert a raw [AcousticEvent]'s mic-frame azimuth into a true-north
 * world bearing (0–360°). Returns null when the event is absent, flagged
 * `valid = false` by the Pi, or the compass hasn't fixed yet — in all
 * those cases the upstream alert UI should show nothing rather than
 * compute a bearing from garbage (a missing compass fix must never be
 * silently substituted as 0° / true north — same rule as the localizer).
 *
 * Geometry, single line of math:
 *
 *   world = compass_heading + RigGeometry.MIC_ARRAY_OFFSET_DEG + event.azimuth
 *
 * Why no servo: the mic array is bolted to the fixed tripod, **below**
 * the camera's pan/tilt head. The servos rotate the head only — the
 * mics don't move with them. The only things that affect the world
 * bearing are how the whole tripod is pointed (the compass) and the
 * hardware-fixed angular offset between the compass's north-reference
 * axis and the mic array's azimuth-zero axis ([RigGeometry.MIC_ARRAY_OFFSET_DEG],
 * a one-time calibration constant — see its KDoc).
 *
 * The function lives next to [localizeTarget] because both depend on
 * [RigGeometry] and follow the same null-on-missing-input contract; it
 * isn't strictly "ballistics" but the package is the natural home for
 * rig-geometry-aware sensor math.
 */
fun worldBearingFromAcousticEvent(
    event: AcousticEvent?,
    compassHeadingDeg: Float?,
): Double? {
    if (event == null || !event.valid) return null
    if (compassHeadingDeg == null) return null
    return normalizeDeg(
        compassHeadingDeg.toDouble() +
            RigGeometry.MIC_ARRAY_OFFSET_DEG +
            event.azimuthDeg.toDouble()
    )
}
