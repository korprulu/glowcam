package dev.glowcam

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Portrait beautification on ARGB pixels, pure Kotlin (no Android deps, unit-testable).
 *
 * 1. Soft skin mask from YCbCr colour ranges.
 * 2. Edge-preserving smoothing via a fast guided filter (He et al.), guide = luma.
 * 3. Frequency separation: colour comes fully from the smoothed image, luma keeps part of its fine
 *    detail (pores) but not blemishes; smoothing is relieved where the local luma varies a lot (features).
 * 4. Whitening (lift toward white) and rosy tint, both weighted by the skin mask.
 */
object BeautyFilter {

    fun apply(src: Pixels, params: BeautyParams): Pixels {
        val p = params.clamped()
        if (p.isIdentity) return Pixels(src.data.copyOf(), src.width, src.height)

        val w = src.width
        val h = src.height
        val s = src.data
        val longSide = max(w, h)
        val sub = min(4, max(1, ceil(longSide / 500.0).toInt()))
        val sw = (w + sub - 1) / sub
        val sh = (h + sub - 1) / sub

        // Downsample (box average) RGB to the coarse grid.
        val sr = FloatArray(sw * sh)
        val sg = FloatArray(sw * sh)
        val sb = FloatArray(sw * sh)
        for (gy in 0 until sh) {
            val y0 = gy * sub
            val y1 = min(h, y0 + sub)
            for (gx in 0 until sw) {
                val x0 = gx * sub
                val x1 = min(w, x0 + sub)
                var r = 0f
                var g = 0f
                var b = 0f
                for (y in y0 until y1) for (x in x0 until x1) {
                    val c = s[y * w + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                val n = ((y1 - y0) * (x1 - x0)) * 255f
                val i = gy * sw + gx
                sr[i] = r / n
                sg[i] = g / n
                sb[i] = b / n
            }
        }

        // Skin mask on the coarse grid, feathered.
        val rad = max(1, Math.round(longSide * 0.006f / sub))
        val mask = boxMean(FloatArray(sw * sh) { skinScore(sr[it], sg[it], sb[it]) }, sw, sh, rad * 2)

        // Guided filter coefficients per channel (guide = luma).
        val guide = FloatArray(sw * sh) { 0.299f * sr[it] + 0.587f * sg[it] + 0.114f * sb[it] }
        val eps = (0.04f + 0.12f * p.smooth).let { it * it }
        val meanI = boxMean(guide, sw, sh, rad)
        val corrI = boxMean(FloatArray(guide.size) { guide[it] * guide[it] }, sw, sh, rad)
        val varI = FloatArray(guide.size) { corrI[it] - meanI[it] * meanI[it] }

        fun coeffs(ch: FloatArray): Pair<FloatArray, FloatArray> {
            val meanP = boxMean(ch, sw, sh, rad)
            val corrIp = boxMean(FloatArray(ch.size) { guide[it] * ch[it] }, sw, sh, rad)
            val a = FloatArray(ch.size)
            val b = FloatArray(ch.size)
            for (i in ch.indices) {
                a[i] = (corrIp[i] - meanI[i] * meanP[i]) / (varI[i] + eps)
                b[i] = meanP[i] - a[i] * meanI[i]
            }
            return boxMean(a, sw, sh, rad) to boxMean(b, sw, sh, rad)
        }

        val (ar, br) = coeffs(sr)
        val (ag, bg) = coeffs(sg)
        val (ab, bb) = coeffs(sb)

        // Per-pixel bilinear upsample of coefficients + blend.
        val xi0 = IntArray(w)
        val xi1 = IntArray(w)
        val xw = FloatArray(w)
        for (x in 0 until w) {
            val u = ((x + 0.5f) / sub - 0.5f).coerceIn(0f, (sw - 1).toFloat())
            val i0 = u.toInt()
            xi0[x] = i0
            xi1[x] = min(sw - 1, i0 + 1)
            xw[x] = u - i0
        }
        val out = IntArray(w * h)
        val smoothK = p.smooth * 0.85f
        for (y in 0 until h) {
            val v = ((y + 0.5f) / sub - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val j0 = v.toInt()
            val j1 = min(sh - 1, j0 + 1)
            val wy = v - j0
            for (x in 0 until w) {
                val a0 = xi0[x]
                val a1 = xi1[x]
                val wx = xw[x]
                fun up(t: FloatArray): Float {
                    val top = t[j0 * sw + a0] * (1 - wx) + t[j0 * sw + a1] * wx
                    val bot = t[j1 * sw + a0] * (1 - wx) + t[j1 * sw + a1] * wx
                    return top * (1 - wy) + bot * wy
                }
                val c = s[y * w + x]
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                val m = up(mask)
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val sr0 = up(ar) * luma + up(br)
                val sg0 = up(ag) * luma + up(bg)
                val sb0 = up(ab) * luma + up(bb)
                val d = luma - (0.299f * sr0 + 0.587f * sg0 + 0.114f * sb0)
                val keep = BeautyTuning.detailKeep(d, p.smooth) * d
                val std = sqrt(max(0f, up(varI)))
                val k = m * smoothK * BeautyTuning.strengthScale(std)
                var nr = r + (sr0 + keep - r) * k
                var ng = g + (sg0 + keep - g) * k
                var nb = b + (sb0 + keep - b) * k
                val wk = m * p.whiten * 0.4f
                nr += (1f - nr) * wk
                ng += (1f - ng) * wk
                nb += (1f - nb) * wk
                val rk = m * p.rosy
                nr += 0.06f * rk
                ng -= 0.01f * rk
                nb += 0.015f * rk
                out[y * w + x] = (0xFF shl 24) or (to8(nr) shl 16) or (to8(ng) shl 8) or to8(nb)
            }
        }
        return Pixels(out, w, h)
    }

    private fun to8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    /** Soft skin membership in 0..1 from YCbCr (BT.601, inputs 0..1). */
    internal fun skinScore(r: Float, g: Float, b: Float): Float {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f + (-0.168736f * r - 0.331264f * g + 0.5f * b) * 255f
        val cr = 128f + (0.5f * r - 0.418688f * g - 0.081312f * b) * 255f
        val sCb = trapezoid(cb, 77f, 127f, 8f)
        val sCr = trapezoid(cr, 133f, 173f, 8f)
        val sY = ramp(y * 255f, 40f, 70f)
        return sCb * sCr * sY
    }

    private fun trapezoid(v: Float, lo: Float, hi: Float, soft: Float): Float =
        min(ramp(v, lo - soft, lo), ramp(-v, -hi - soft, -hi))

    private fun ramp(v: Float, a: Float, b: Float): Float = ((v - a) / (b - a)).coerceIn(0f, 1f)

    /** Mean over a (2r+1)^2 window with border-aware normalisation; separable running sums. */
    internal fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0.0
            for (x in 0..min(r, w - 1)) sum += src[row + x]
            for (x in 0 until w) {
                val lo = max(0, x - r)
                val hi = min(w - 1, x + r)
                tmp[row + x] = (sum / (hi - lo + 1)).toFloat()
                if (x + r + 1 < w) sum += src[row + x + r + 1]
                if (x - r >= 0) sum -= src[row + x - r]
            }
        }
        val out = FloatArray(src.size)
        for (x in 0 until w) {
            var sum = 0.0
            for (y in 0..min(r, h - 1)) sum += tmp[y * w + x]
            for (y in 0 until h) {
                val lo = max(0, y - r)
                val hi = min(h - 1, y + r)
                out[y * w + x] = (sum / (hi - lo + 1)).toFloat()
                if (y + r + 1 < h) sum += tmp[(y + r + 1) * w + x]
                if (y - r >= 0) sum -= tmp[(y - r) * w + x]
            }
        }
        return out
    }
}
