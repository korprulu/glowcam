package dev.glowcam

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeautyFilterTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun luma(c: Int) = 0.299 * red(c) + 0.587 * green(c) + 0.114 * blue(c)

    private val skin = intArrayOf(224, 172, 150)

    private fun noisySkin(w: Int, h: Int, amp: Int, seed: Long = 1): Pixels {
        val rnd = Random(seed)
        val d = IntArray(w * h) {
            val n = rnd.nextInt(2 * amp + 1) - amp
            rgb(
                (skin[0] + n).coerceIn(0, 255),
                (skin[1] + n).coerceIn(0, 255),
                (skin[2] + n).coerceIn(0, 255),
            )
        }
        return Pixels(d, w, h)
    }

    private fun lumaVariance(p: Pixels, margin: Int = 0): Double {
        val v = ArrayList<Double>()
        for (y in margin until p.height - margin) for (x in margin until p.width - margin)
            v.add(luma(p.data[y * p.width + x]))
        val mean = v.average()
        return v.sumOf { (it - mean) * (it - mean) } / v.size
    }

    @Test
    fun zeroStrengthIsIdentity() {
        val src = noisySkin(64, 48, 20)
        val out = BeautyFilter.apply(src, BeautyParams(0f, 0f, 0f))
        assertArrayEquals(src.data, out.data)
    }

    @Test
    fun smoothingReducesSkinNoise() {
        val src = noisySkin(120, 160, 25)
        val out = BeautyFilter.apply(src, BeautyParams(smooth = 1f, whiten = 0f, rosy = 0f))
        val before = lumaVariance(src, 10)
        val after = lumaVariance(out, 10)
        assertTrue("variance $before -> $after", after < before * 0.35)
    }

    @Test
    fun smoothingLeavesNonSkinUntouched() {
        val rnd = Random(3)
        val d = IntArray(100 * 100) { rgb(20 + rnd.nextInt(20), 40 + rnd.nextInt(20), 200 + rnd.nextInt(40)) }
        val src = Pixels(d, 100, 100)
        val out = BeautyFilter.apply(src, BeautyParams(1f, 0f, 0f))
        var maxDiff = 0
        for (i in d.indices) {
            maxDiff = maxOf(maxDiff, kotlin.math.abs(red(d[i]) - red(out.data[i])), kotlin.math.abs(blue(d[i]) - blue(out.data[i])))
        }
        assertTrue("max diff $maxDiff", maxDiff <= 2)
    }

    @Test
    fun smoothingPreservesStrongEdges() {
        // Left: skin-tone, right: dark brown; sharp vertical edge in the middle.
        val w = 120
        val h = 120
        val left = rgb(224, 172, 150)
        val right = rgb(110, 70, 55)
        val src = Pixels(IntArray(w * h) { if (it % w < w / 2) left else right }, w, h)
        val out = BeautyFilter.apply(src, BeautyParams(1f, 0f, 0f))
        val y = h / 2
        val contrastBefore = luma(src.data[y * w + w / 2 - 1]) - luma(src.data[y * w + w / 2])
        val contrastAfter = luma(out.data[y * w + w / 2 - 1]) - luma(out.data[y * w + w / 2])
        assertTrue("edge $contrastBefore -> $contrastAfter", contrastAfter > contrastBefore * 0.6)
    }

    @Test
    fun whiteningBrightensSkinOnly() {
        val src = noisySkin(80, 80, 0)
        val out = BeautyFilter.apply(src, BeautyParams(0f, 1f, 0f))
        assertTrue(luma(out.data[40 * 80 + 40]) > luma(src.data[40 * 80 + 40]) + 8)

        val blue = Pixels(IntArray(64 * 64) { rgb(10, 30, 220) }, 64, 64)
        val outBlue = BeautyFilter.apply(blue, BeautyParams(0f, 1f, 0f))
        assertEquals(blue.data[0], outBlue.data[0])
    }

    @Test
    fun rosyIncreasesRedOnSkin() {
        val src = noisySkin(80, 80, 0)
        val out = BeautyFilter.apply(src, BeautyParams(0f, 0f, 1f))
        val c = 40 * 80 + 40
        assertTrue(red(out.data[c]) > red(src.data[c]))
        assertTrue(green(out.data[c]) <= green(src.data[c]))
    }

    @Test
    fun outputKeepsSizeAndOpaqueAlphaForOddDimensions() {
        val src = noisySkin(37, 53, 10)
        val out = BeautyFilter.apply(src, BeautyParams())
        assertEquals(37, out.width)
        assertEquals(53, out.height)
        assertTrue(out.data.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun largeImageProcessesWithinBudget() {
        val src = noisySkin(1600, 1200, 15)
        val t0 = System.nanoTime()
        val out = BeautyFilter.apply(src, BeautyParams())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1600 * 1200, out.data.size)
        assertTrue("took $ms ms", ms < 10_000)
    }

    @Test
    fun boxMeanMatchesNaive() {
        val w = 13
        val h = 9
        val rnd = Random(7)
        val src = FloatArray(w * h) { rnd.nextFloat() }
        val r = 2
        val fast = BeautyFilter.boxMean(src, w, h, r)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            var n = 0
            for (yy in maxOf(0, y - r)..minOf(h - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(w - 1, x + r)) {
                s += src[yy * w + xx]; n++
            }
            assertEquals(s / n, fast[y * w + x].toDouble(), 1e-5)
        }
    }

    @Test
    fun skinScoreDiscriminates() {
        assertTrue(BeautyFilter.skinScore(224 / 255f, 172 / 255f, 150 / 255f) > 0.9f)
        assertTrue(BeautyFilter.skinScore(0f, 0f, 1f) < 0.01f)
        assertTrue(BeautyFilter.skinScore(0.1f, 0.8f, 0.1f) < 0.01f)
        assertTrue(BeautyFilter.skinScore(0f, 0f, 0f) < 0.01f)
    }
}
