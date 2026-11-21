package dev.glowcam

import kotlin.math.abs

/**
 * Constants and curves of the frequency-separation skin smoothing, shared by the GPU preview shader
 * (which embeds the constants) and the CPU filter used for saved photos, so both look alike.
 *
 * Skin = low frequency (tone and colour, smoothed fully) + fine luma detail (pores, kept in part).
 */
object BeautyTuning {
    /** Share of the fine luma detail (pores) kept at zero smoothing strength. */
    const val TEXTURE_KEEP = 0.6f

    /** How much of that share is given up at full smoothing strength. */
    const val TEXTURE_KEEP_DROP = 0.5f

    /** Luma deviations from the smoothed value below DETAIL_LO count as pores, above DETAIL_HI as blemishes. */
    const val DETAIL_LO = 0.02f
    const val DETAIL_HI = 0.08f

    /** Local luma deviation range (0..1) in which smoothing is relieved: features such as eyes, brows, lips, hair. */
    const val STD_LO = 0.04f
    const val STD_HI = 0.12f

    /** Largest share of the smoothing strength taken away in high-variance areas. */
    const val EDGE_RELIEF = 0.6f

    fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Share of fine detail kept for a smoothing strength in 0..1. */
    fun textureKeep(smooth: Float): Float = TEXTURE_KEEP * (1f - TEXTURE_KEEP_DROP * smooth.coerceIn(0f, 1f))

    /** Share of the luma detail [d] (pixel luma minus smoothed luma) that stays: pores yes, blemishes no. */
    fun detailKeep(d: Float, smooth: Float): Float =
        textureKeep(smooth) * (1f - smoothstep(DETAIL_LO, DETAIL_HI, abs(d)))

    /** Multiplier for the smoothing strength given the local luma standard deviation. */
    fun strengthScale(std: Float): Float = 1f - EDGE_RELIEF * smoothstep(STD_LO, STD_HI, std)
}
