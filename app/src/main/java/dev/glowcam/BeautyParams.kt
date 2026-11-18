package dev.glowcam

/** Beauty strengths, each in 0..1. */
data class BeautyParams(
    val smooth: Float = 0.6f,
    val whiten: Float = 0.3f,
    val rosy: Float = 0.2f,
) {
    val isIdentity: Boolean get() = smooth <= 0f && whiten <= 0f && rosy <= 0f

    fun clamped() = BeautyParams(smooth.coerceIn(0f, 1f), whiten.coerceIn(0f, 1f), rosy.coerceIn(0f, 1f))

    companion object {
        const val MAX_PROGRESS = 100

        fun fromProgress(smooth: Int, whiten: Int, rosy: Int) = BeautyParams(
            smooth.coerceIn(0, MAX_PROGRESS) / MAX_PROGRESS.toFloat(),
            whiten.coerceIn(0, MAX_PROGRESS) / MAX_PROGRESS.toFloat(),
            rosy.coerceIn(0, MAX_PROGRESS) / MAX_PROGRESS.toFloat(),
        )
    }
}
