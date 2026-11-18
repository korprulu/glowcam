package dev.glowcam

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Zoom-ratio helpers (pure, unit-testable). Slider positions map logarithmically so 1x->2x feels like 2x->4x. */
object ZoomMath {
    private val PRESETS = floatArrayOf(1f, 2f, 3f, 5f)

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

    /** Tap behaviour: step to the next preset above the current ratio, wrapping back to the minimum. */
    fun nextPreset(current: Float, min: Float, max: Float): Float {
        val candidates = PRESETS.filter { it in min..max }
        val next = candidates.firstOrNull { it > current + 0.05f }
        return next ?: min
    }

    fun format(ratio: Float): String =
        if (abs(ratio - ratio.roundToInt()) < 0.05f) "${ratio.roundToInt()}x" else String.format(java.util.Locale.US, "%.1fx", ratio)
}
