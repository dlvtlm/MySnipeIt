package com.example.mysnipeit.data.ballistics

import com.example.mysnipeit.data.models.AcousticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-math tests for [worldBearingFromAcousticEvent]. All cases assume
 * the current [RigGeometry.MIC_ARRAY_OFFSET_DEG] default (0.0). If the
 * physical rig measurement flips that constant, these tests will fail
 * loudly — that's the desired behaviour: the field-calibration value
 * must be plugged in deliberately, not absorbed silently.
 */
class AcousticBearingTest {

    private fun event(azim: Float, valid: Boolean = true) = AcousticEvent(
        type = "acoustic_event",
        timestampUs = 0L,
        azimuthDeg = azim,
        confidence = 0.9f,
        peakAmplitude = 0.5f,
        durationMs = 4.0f,
        valid = valid,
    )

    // --- Happy path ----------------------------------------------------------

    @Test
    fun `compass 0, event 0 -> world 0`() {
        assertEquals(0.0, worldBearingFromAcousticEvent(event(0f), 0f)!!, 1e-9)
    }

    @Test
    fun `compass 0, event 42 -> world 42`() {
        assertEquals(42.0, worldBearingFromAcousticEvent(event(42f), 0f)!!, 1e-9)
    }

    @Test
    fun `compass 90, event 30 -> world 120`() {
        assertEquals(120.0, worldBearingFromAcousticEvent(event(30f), 90f)!!, 1e-9)
    }

    // --- Wraparound past 360 -------------------------------------------------

    @Test
    fun `compass 350 + event 20 wraps to 10`() {
        assertEquals(10.0, worldBearingFromAcousticEvent(event(20f), 350f)!!, 1e-9)
    }

    @Test
    fun `compass 300 + event 200 wraps to 140`() {
        assertEquals(140.0, worldBearingFromAcousticEvent(event(200f), 300f)!!, 1e-9)
    }

    @Test
    fun `compass 359_5 + event 1 wraps to 0_5`() {
        assertEquals(0.5, worldBearingFromAcousticEvent(event(1f), 359.5f)!!, 1e-5)
    }

    // --- Null guards (must never return a bearing) ---------------------------

    @Test
    fun `null event returns null`() {
        assertNull(worldBearingFromAcousticEvent(null, 100f))
    }

    @Test
    fun `null compass returns null`() {
        // The "magnetometer not fixed yet" case — bearing must never be
        // silently treated as 0° (true north).
        assertNull(worldBearingFromAcousticEvent(event(42f), null))
    }

    @Test
    fun `valid=false on the event returns null`() {
        assertNull(worldBearingFromAcousticEvent(event(42f, valid = false), 0f))
    }

    @Test
    fun `null event takes precedence over null compass`() {
        // Defensive: even with both nullable, we don't accidentally return
        // 0 or NaN. Both paths return null cleanly.
        assertNull(worldBearingFromAcousticEvent(null, null))
    }

    // --- Output is always normalised to [0, 360) -----------------------------

    @Test
    fun `result is always in 0 to 360 range`() {
        val cases = listOf(
            0f to 0f, 90f to 30f, 180f to 200f, 359f to 359f, 350f to 20f,
            0.5f to 359.9f, 1f to 360f,  // event=360 should normalise too
        )
        for ((compass, azim) in cases) {
            val r = worldBearingFromAcousticEvent(event(azim), compass)!!
            assert(r >= 0.0 && r < 360.0) { "out-of-range bearing $r for compass=$compass azim=$azim" }
        }
    }
}
