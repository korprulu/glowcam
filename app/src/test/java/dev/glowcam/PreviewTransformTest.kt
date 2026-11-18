package dev.glowcam

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTransformTest {
    private fun assertPair(ex: Float, ey: Float, p: Pair<Float, Float>) {
        assertEquals(ex, p.first, 1e-5f)
        assertEquals(ey, p.second, 1e-5f)
    }

    @Test
    fun noRotationIsIdentity() {
        assertPair(0.3f, 0.8f, PreviewTransform.displayToBuffer(0.3f, 0.8f, 0, false))
    }

    @Test
    fun clockwise90PutsBufferTopLeftAtDisplayTopRight() {
        // Buffer top-left (0,1) must end up at display top-right (1,1) after a 90 degree clockwise turn,
        // so looking up display (1,1) has to fetch buffer (0,1).
        assertPair(0f, 1f, PreviewTransform.displayToBuffer(1f, 1f, 90, false))
        // display top-left (0,1) <- buffer bottom-left (0,0)
        assertPair(0f, 0f, PreviewTransform.displayToBuffer(0f, 1f, 90, false))
        // display bottom-right (1,0) <- buffer top-right (1,1)
        assertPair(1f, 1f, PreviewTransform.displayToBuffer(1f, 0f, 90, false))
    }

    @Test
    fun rotation180FlipsBothAxes() {
        assertPair(0.8f, 0.3f, PreviewTransform.displayToBuffer(0.2f, 0.7f, 180, false))
    }

    @Test
    fun rotation270IsInverseOf90() {
        for ((x, y) in listOf(0.1f to 0.9f, 0.6f to 0.25f)) {
            val (bx, by) = PreviewTransform.displayToBuffer(x, y, 90, false)
            // buffer->display for 90 is display->buffer for 270
            assertPair(x, y, PreviewTransform.displayToBuffer(bx, by, 270, false))
        }
    }

    @Test
    fun mirrorFlipsDisplayHorizontallyBeforeRotation() {
        assertPair(0.7f, 0.4f, PreviewTransform.displayToBuffer(0.3f, 0.4f, 0, true))
        // mirrored 90: display (1,1) -> mirrored (0,1) -> buffer for 90 of (0,1)
        val plain = PreviewTransform.displayToBuffer(0f, 1f, 90, false)
        assertPair(plain.first, plain.second, PreviewTransform.displayToBuffer(1f, 1f, 90, true))
    }

    @Test
    fun cropScaleFillsViewAndOverflowsOneAxis() {
        // 3:4 frame in a taller 1080x2400 view: height-limited, sides cropped (sx > 1, sy == 1).
        val (sx, sy) = PreviewTransform.cropScale(1080f, 2400f, 480f, 640f)
        assertEquals(1f, sy, 1e-5f)
        assertEquals(true, sx > 1f)
        // frame wider than view aspect: width-limited
        val (wx, wy) = PreviewTransform.cropScale(1080f, 1200f, 480f, 640f)
        assertEquals(1f, wx, 1e-5f)
        assertEquals(true, wy > 1f)
    }

    @Test
    fun displaySizeSwapsForQuarterTurns() {
        assertEquals(480 to 640, PreviewTransform.displaySize(640, 480, 90))
        assertEquals(480 to 640, PreviewTransform.displaySize(640, 480, 270))
        assertEquals(640 to 480, PreviewTransform.displaySize(640, 480, 0))
        assertEquals(640 to 480, PreviewTransform.displaySize(640, 480, 180))
    }

    @Test
    fun blurParametersScaleWithResolution() {
        assertEquals(1.5f, PreviewTransform.blurSpacing(960, 720), 1e-5f)
        assertEquals(3f, PreviewTransform.blurSpacing(1920, 1440), 1e-5f)
        assertEquals(1f, PreviewTransform.blurSpacing(320, 240), 1e-5f)
        assertEquals(0.04f, PreviewTransform.rangeSigma(0f), 1e-5f)
        assertEquals(0.16f, PreviewTransform.rangeSigma(1f), 1e-5f)
    }
}
