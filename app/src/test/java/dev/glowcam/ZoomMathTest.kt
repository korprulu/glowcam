package dev.glowcam

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {
    @Test
    fun fractionEndpointsMapToRange() {
        assertEquals(1f, ZoomMath.ratioFromFraction(0f, 1f, 8f), 1e-4f)
        assertEquals(8f, ZoomMath.ratioFromFraction(1f, 1f, 8f), 1e-4f)
        assertEquals(1f, ZoomMath.ratioFromFraction(-3f, 1f, 8f), 1e-4f)
        assertEquals(8f, ZoomMath.ratioFromFraction(9f, 1f, 8f), 1e-4f)
    }

    @Test
    fun mappingIsLogarithmicAndInvertible() {
        // Halfway on a 1..4 range is 2x.
        assertEquals(2f, ZoomMath.ratioFromFraction(0.5f, 1f, 4f), 1e-4f)
        for (r in listOf(1f, 1.7f, 3.3f, 6f)) {
            val f = ZoomMath.fractionFromRatio(r, 1f, 6f)
            assertEquals(r, ZoomMath.ratioFromFraction(f, 1f, 6f), 1e-3f)
        }
    }

    @Test
    fun degenerateRangeIsSafe() {
        assertEquals(1f, ZoomMath.ratioFromFraction(0.7f, 1f, 1f), 0f)
        assertEquals(0f, ZoomMath.fractionFromRatio(1f, 1f, 1f), 0f)
    }

    @Test
    fun tapCyclesPresetsAndWraps() {
        assertEquals(2f, ZoomMath.nextPreset(1f, 1f, 8f), 0f)
        assertEquals(3f, ZoomMath.nextPreset(2f, 1f, 8f), 0f)
        assertEquals(5f, ZoomMath.nextPreset(3f, 1f, 8f), 0f)
        assertEquals(1f, ZoomMath.nextPreset(5f, 1f, 8f), 0f)
        assertEquals(2f, ZoomMath.nextPreset(1.4f, 1f, 8f), 0f)
    }

    @Test
    fun tapRespectsCameraRange() {
        assertEquals(1f, ZoomMath.nextPreset(2f, 1f, 2f), 0f)   // max 2x: 1 -> 2 -> 1
        assertEquals(1f, ZoomMath.nextPreset(1f, 1f, 1f), 0f)   // no zoom support
        assertEquals(0.6f, ZoomMath.nextPreset(1f, 0.6f, 1f), 0f) // ultra-wide minimum
    }

    @Test
    fun formatting() {
        assertEquals("1x", ZoomMath.format(1f))
        assertEquals("2x", ZoomMath.format(2.02f))
        assertEquals("1.5x", ZoomMath.format(1.5f))
        assertEquals("0.6x", ZoomMath.format(0.6f))
    }
}
