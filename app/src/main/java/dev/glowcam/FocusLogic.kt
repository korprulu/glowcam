package dev.glowcam

import kotlin.math.abs

/** Camera2 CONTROL_AF_STATE values (numeric so the logic stays free of Android types). */
object AfState {
    const val PASSIVE_SCAN = 1
    const val ACTIVE_SCAN = 3
    const val FOCUSED_LOCKED = 4
    const val NOT_FOCUSED_LOCKED = 5

    /** A "focus change event": the AF state changed into a scanning or locked state. */
    fun isFocusEvent(previous: Int?, current: Int?): Boolean =
        current != null && current != previous &&
            (current == PASSIVE_SCAN || current == ACTIVE_SCAN || current == FOCUSED_LOCKED || current == NOT_FOCUSED_LOCKED)
}

/**
 * Focus-ring state. Positions are normalized (0..1) in the displayed camera frame.
 * - default state: ring (re)appears at the centre on a focus event, fades after [FADE_MS] without one
 * - custom state (user tapped): ring stays and follows the tracked point until tracking is lost
 */
class FocusController {
    var custom = false
        private set
    var visible = false
        private set
    var nx = 0.5f
        private set
    var ny = 0.5f
        private set
    private var lastChange = 0L

    fun onFocusEvent(now: Long) {
        if (!custom) {
            nx = 0.5f
            ny = 0.5f
        }
        visible = true
        lastChange = now
    }

    fun onUserTap(x: Float, y: Float, now: Long) {
        custom = true
        nx = x.coerceIn(0f, 1f)
        ny = y.coerceIn(0f, 1f)
        visible = true
        lastChange = now
    }

    fun onTrack(x: Float, y: Float) {
        if (!custom) return
        nx = x.coerceIn(0f, 1f)
        ny = y.coerceIn(0f, 1f)
    }

    /** The tapped point left the frame or could not be followed: back to centre, custom cancelled. */
    fun onTrackLost(now: Long) {
        custom = false
        nx = 0.5f
        ny = 0.5f
        visible = true
        lastChange = now
    }

    fun reset() {
        custom = false
        visible = false
        nx = 0.5f
        ny = 0.5f
    }

    /** Returns true when the ring just became hidden. */
    fun tick(now: Long): Boolean {
        if (visible && !custom && now - lastChange >= FADE_MS) {
            visible = false
            return true
        }
        return false
    }

    companion object {
        const val FADE_MS = 3000L
    }
}

/** Maps between view coordinates and the normalized camera frame (drawn by [PreviewTransform.fitRect]). */
object FocusMapping {
    /** Null when the point is outside the picture, e.g. in the black area below it. */
    fun viewToFrame(
        x: Float, y: Float, viewW: Float, viewH: Float, frameW: Float, frameH: Float, topInset: Float = 0f,
    ): Pair<Float, Float>? {
        val r = PreviewTransform.fitRect(viewW, viewH, frameW, frameH, topInset)
        val nx = (x - r.left) / r.width
        val ny = (y - r.top) / r.height
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return nx to ny
    }

    fun frameToView(
        nx: Float, ny: Float, viewW: Float, viewH: Float, frameW: Float, frameH: Float, topInset: Float = 0f,
    ): Pair<Float, Float> {
        val r = PreviewTransform.fitRect(viewW, viewH, frameW, frameH, topInset)
        return (r.left + nx * r.width) to (r.top + ny * r.height)
    }
}

/**
 * Follows a tapped point from frame to frame by template matching (sum of absolute differences).
 * The camera HAL does not track a focus point as the scene moves, so this does it in software.
 * Not thread-safe: use from one thread.
 */
class FocusTracker(
    private val half: Int = 12,
    private val search: Int = 32,
    private val maxScore: Float = 30f,
    private val edgeMargin: Int = 6,
    private val maxMisses: Int = 3,
) {
    private var template: IntArray? = null
    private var cx = 0
    private var cy = 0
    private var misses = 0

    val active: Boolean get() = template != null

    fun start(gray: IntArray, w: Int, h: Int, x: Int, y: Int) {
        cx = x.coerceIn(0, w - 1)
        cy = y.coerceIn(0, h - 1)
        template = extract(gray, w, h, cx, cy)
        misses = 0
    }

    fun stop() {
        template = null
    }

    /** New position in pixels, or null when the point was lost (tracking is then stopped). */
    fun update(gray: IntArray, w: Int, h: Int): Pair<Int, Int>? {
        val t = template ?: return null
        val side = 2 * half + 1
        val n = side * side
        var best = Int.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in -search..search) {
            val py = cy + dy
            if (py < 0 || py >= h) continue
            for (dx in -search..search) {
                val px = cx + dx
                if (px < 0 || px >= w) continue
                var sad = 0
                var i = 0
                loop@ for (yy in -half..half) {
                    val ry = (py + yy).coerceIn(0, h - 1) * w
                    for (xx in -half..half) {
                        sad += abs(gray[ry + (px + xx).coerceIn(0, w - 1)] - t[i++])
                    }
                    if (sad >= best) break@loop
                }
                if (sad < best) {
                    best = sad
                    bx = px
                    by = py
                }
            }
        }
        val score = best.toFloat() / n
        val outOfFrame = bx < edgeMargin || bx >= w - edgeMargin || by < edgeMargin || by >= h - edgeMargin
        if (outOfFrame) {
            template = null
            return null
        }
        if (score > maxScore) {
            // Tolerate a few bad frames (blur, hand shake) before giving up.
            misses++
            if (misses >= maxMisses) {
                template = null
                return null
            }
            return cx to cy
        }
        misses = 0
        if (score < maxScore / 3f) template = extract(gray, w, h, bx, by) // follow gradual appearance changes
        cx = bx
        cy = by
        return bx to by
    }

    private fun extract(gray: IntArray, w: Int, h: Int, x: Int, y: Int): IntArray {
        val side = 2 * half + 1
        val out = IntArray(side * side)
        var i = 0
        for (yy in -half..half) {
            val ry = (y + yy).coerceIn(0, h - 1) * w
            for (xx in -half..half) out[i++] = gray[ry + (x + xx).coerceIn(0, w - 1)]
        }
        return out
    }

    companion object {
        fun toGray(p: Pixels): IntArray = IntArray(p.data.size) {
            val c = p.data[it]
            (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
    }
}
