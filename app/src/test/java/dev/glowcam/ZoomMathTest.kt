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
    fun presetsFollowCameraRange() {
        assertEquals(listOf(0.5f, 1f, 2f), ZoomMath.presets(0.5f, 8f))
        assertEquals(listOf(0.6f, 1f, 2f), ZoomMath.presets(0.6f, 10f))
        assertEquals(listOf(1f, 2f), ZoomMath.presets(1f, 4f))
        assertEquals(listOf(1f), ZoomMath.presets(1f, 1.5f))
        assertEquals(listOf(0.5f, 1f), ZoomMath.presets(0.5f, 1.5f))
    }

    @Test
    fun selectedButtonIsHighestPresetNotAboveRatio() {
        val p = listOf(0.5f, 1f, 2f)
        assertEquals(0, ZoomMath.selectedIndex(p, 0.5f))
        assertEquals(0, ZoomMath.selectedIndex(p, 0.9f))
        assertEquals(1, ZoomMath.selectedIndex(p, 1f))
        assertEquals(1, ZoomMath.selectedIndex(p, 1.4f))
        assertEquals(2, ZoomMath.selectedIndex(p, 2f))
        assertEquals(2, ZoomMath.selectedIndex(p, 7f))
    }

    @Test
    fun lowerBoundCanBeHalfX() {
        assertEquals(0.5f, ZoomMath.ratioFromFraction(0f, 0.5f, 8f), 1e-4f)
        assertEquals(1f, ZoomMath.ratioFromFraction(0.25f, 0.5f, 8f), 1e-3f)
    }

    @Test
    fun formatting() {
        assertEquals("1×", ZoomMath.format(1f))
        assertEquals("2×", ZoomMath.format(2.02f))
        assertEquals("1.4×", ZoomMath.format(1.4f))
        assertEquals("0.5×", ZoomMath.format(0.5f))
        assertEquals(".5", ZoomMath.plain(0.5f))
        assertEquals("1", ZoomMath.plain(1f))
        assertEquals("2", ZoomMath.plain(2f))
    }
}
