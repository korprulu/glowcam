package dev.glowcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeautyTuningTest {
    @Test
    fun smoothstepIsClampedAndMonotonic() {
        assertEquals(0f, BeautyTuning.smoothstep(1f, 2f, 0f), 0f)
        assertEquals(1f, BeautyTuning.smoothstep(1f, 2f, 3f), 0f)
        assertEquals(0.5f, BeautyTuning.smoothstep(1f, 2f, 1.5f), 1e-6f)
    }

    @Test
    fun strongerSmoothingKeepsLessTexture() {
        assertTrue(BeautyTuning.textureKeep(1f) < BeautyTuning.textureKeep(0f))
        assertTrue(BeautyTuning.textureKeep(1f) > 0f)
    }

    @Test
    fun poresAreKeptButBlemishesAreNot() {
        val pore = BeautyTuning.detailKeep(0.01f, 0.6f)
        val blemish = BeautyTuning.detailKeep(0.2f, 0.6f)
        assertEquals(BeautyTuning.textureKeep(0.6f), pore, 1e-6f)
        assertEquals(0f, blemish, 0f)
        assertEquals(BeautyTuning.detailKeep(0.05f, 0.6f), BeautyTuning.detailKeep(-0.05f, 0.6f), 0f)
    }

    @Test
    fun smoothingIsRelievedOnlyWhereLumaVaries() {
        assertEquals(1f, BeautyTuning.strengthScale(0.01f), 0f)
        assertEquals(1f - BeautyTuning.EDGE_RELIEF, BeautyTuning.strengthScale(0.5f), 1e-6f)
        assertTrue(BeautyTuning.strengthScale(0.08f) in (1f - BeautyTuning.EDGE_RELIEF)..1f)
    }
}
