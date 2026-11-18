package dev.glowcam

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Zoom-ratio helpers (pure, unit-testable). Slider positions map logarithmically so 1x->2x feels like 2x->4x. */
object ZoomMath {
    fun ratioFromFraction(fraction: Float, min: Float, max: Float): Float {
        if (max <= min) return min
        val f = fraction.coerceIn(0f, 1f)
        return (min * (max / min).toDouble().pow(f.toDouble())).toFloat().coerceIn(min, max)
    }

    fun fractionFromRatio(ratio: Float, min: Float, max: Float): Float {
        if (max <= min) return 0f
        val r = ratio.coerceIn(min, max)
        return (kotlin.math.ln(r / min) / kotlin.math.ln(max / min)).coerceIn(0f, 1f)
    }

    /** Up to three quick-select buttons: camera minimum (when wider than 1x), 1x, and 2x. */
    fun presets(min: Float, max: Float): List<Float> {
        val out = ArrayList<Float>()
        if (min < 0.95f) out.add(min)
        out.add(if (min > 1f) min else 1f)
        if (max >= 1.95f) out.add(2f)
        return out.distinct()
    }

    /** Index of the button that stands for [ratio]: the highest preset not above it. */
    fun selectedIndex(presets: List<Float>, ratio: Float): Int =
        presets.indexOfLast { it <= ratio + 0.05f }.coerceAtLeast(0)

    /** Selected/bubble label, e.g. `1×`, `1.4×`, `0.5×`. */
    fun format(ratio: Float): String = plain(ratio) .let { if (it.startsWith(".")) "0$it" else it } + "×"

    /** Unselected button label without the multiplication sign, e.g. `.5`, `1`, `2`. */
    fun plain(ratio: Float): String {
        val s = if (abs(ratio - ratio.roundToInt()) < 0.05f) "${ratio.roundToInt()}" else String.format(java.util.Locale.US, "%.1f", ratio)
        return if (s.startsWith("0.")) s.substring(1) else s
    }
}
