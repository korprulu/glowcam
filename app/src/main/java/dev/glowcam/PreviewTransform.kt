package dev.glowcam

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Math behind the GPU preview, kept free of Android types so it can be unit-tested.
 *
 * Coordinates are normalized with the origin at the bottom-left and y pointing up
 * (OpenGL texture convention). "Buffer" space is the camera buffer after SurfaceTexture's own
 * transform; "display" space is the upright picture the user sees.
 */
object PreviewTransform {

    /**
     * Column-major 2x2 matrix for glUniformMatrix2fv that maps display offsets (from the centre) to
     * buffer offsets, given the clockwise rotation the buffer needs to look upright.
     */
    fun rotationMat2(rotationDegrees: Int): FloatArray {
        val a = Math.toRadians(rotationDegrees.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return floatArrayOf(c, s, -s, c)
    }

    /** CPU twin of the vertex shader: display position -> buffer position. */
    fun displayToBuffer(dx: Float, dy: Float, rotationDegrees: Int, mirror: Boolean): Pair<Float, Float> {
        val x = if (mirror) 1f - dx else dx
        val m = rotationMat2(rotationDegrees)
        val ox = x - 0.5f
        val oy = dy - 0.5f
        return (m[0] * ox + m[2] * oy + 0.5f) to (m[1] * ox + m[3] * oy + 0.5f)
    }

    /** Where the whole frame is drawn: fitted (never cropped), top-aligned below [topInset]. */
    class FrameRect(val left: Float, val top: Float, val width: Float, val height: Float)

    fun fitRect(viewW: Float, viewH: Float, frameW: Float, frameH: Float, topInset: Float): FrameRect {
        val s = minOf(viewW / frameW, (viewH - topInset) / frameH)
        val w = frameW * s
        val h = frameH * s
        return FrameRect((viewW - w) / 2f, topInset, w, h)
    }

    /** Clip-space half-extent of the frame quad for [fitRect]. */
    fun quadScale(r: FrameRect, viewW: Float, viewH: Float): Pair<Float, Float> = (r.width / viewW) to (r.height / viewH)

    /** Clip-space centre of the frame quad for [fitRect]. */
    fun quadOffset(r: FrameRect, viewW: Float, viewH: Float): Pair<Float, Float> =
        ((r.left + r.width / 2f) / viewW * 2f - 1f) to (1f - 2f * (r.top + r.height / 2f) / viewH)

    /** Frame size after rotation. */
    fun displaySize(bufW: Int, bufH: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (((rotationDegrees % 180) + 180) % 180 == 90) bufH to bufW else bufW to bufH

    /** Bilateral range sigma (how different a neighbour may be and still be blended in). */
    fun rangeSigma(smooth: Float): Float = 0.04f + 0.12f * smooth.coerceIn(0f, 1f)

    /** Tap spacing in texels so the blur covers about the same picture area at any resolution. */
    fun blurSpacing(bufW: Int, bufH: Int): Float = max(1f, max(bufW, bufH) / 960f * 1.5f)
}
