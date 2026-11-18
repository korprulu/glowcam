package dev.glowcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Zoom button that becomes a slider while held.
 * - tap: [onTap] (cycle presets)
 * - press and hold: expands into a horizontal slider; drag to zoom; release collapses back to the button
 */
class ZoomControl @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var minRatio = 1f
    var maxRatio = 1f
    var ratio = 1f
        set(v) {
            field = v
            invalidate()
        }
    var onTap: (() -> Unit)? = null
    var onRatioChanged: ((Float) -> Unit)? = null

    /** True while the slider is open (finger down after a long press). */
    var expanded = false
        private set

    private val d = resources.displayMetrics.density
    private val btnRadius = 24 * d
    private val trackHalfHeight = 22 * d
    private val sidePad = 24 * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * d
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val textLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 14 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val textDark = Paint(textLight).apply { color = 0xFF000000.toInt() }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downInside = false
    private var longPressed = false
    private val longPress = Runnable {
        if (downInside) {
            longPressed = true
            expanded = true
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private fun supportsZoom() = maxRatio - minRatio > 0.05f

    private fun trackLeft() = sidePad
    private fun trackRight() = width - sidePad

    private fun inButton(x: Float, y: Float): Boolean {
        val dx = x - width / 2f
        val dy = y - height / 2f
        return dx * dx + dy * dy <= (btnRadius + 8 * d) * (btnRadius + 8 * d)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!supportsZoom()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inButton(e.x, e.y)) return false
                downInside = true
                longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (expanded) updateFromX(e.x)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val wasLong = longPressed
                collapse()
                if (!wasLong && downInside) {
                    performClick()
                    onTap?.invoke()
                }
                downInside = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                collapse()
                downInside = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun collapse() {
        expanded = false
        longPressed = false
        invalidate()
    }

    private fun updateFromX(x: Float) {
        val f = ((x - trackLeft()) / (trackRight() - trackLeft())).coerceIn(0f, 1f)
        val r = ZoomMath.ratioFromFraction(f, minRatio, maxRatio)
        ratio = r
        onRatioChanged?.invoke(r)
    }

    override fun onDraw(canvas: Canvas) {
        if (!supportsZoom()) return
        val cx = width / 2f
        val cy = height / 2f
        if (!expanded) {
            canvas.drawCircle(cx, cy, btnRadius, fill)
            canvas.drawCircle(cx, cy, btnRadius, stroke)
            canvas.drawText(ZoomMath.format(ratio), cx, cy + textLight.textSize / 3f, textLight)
            return
        }
        val track = RectF(trackLeft(), cy - trackHalfHeight, trackRight(), cy + trackHalfHeight)
        canvas.drawRoundRect(track, trackHalfHeight, trackHalfHeight, fill)
        canvas.drawRoundRect(track, trackHalfHeight, trackHalfHeight, stroke)
        val f = ZoomMath.fractionFromRatio(ratio, minRatio, maxRatio)
        val tx = trackLeft() + f * (trackRight() - trackLeft())
        // end labels
        canvas.drawText(ZoomMath.format(minRatio), trackLeft() + 28 * d, cy + textLight.textSize / 3f, textLight)
        canvas.drawText(ZoomMath.format(maxRatio), trackRight() - 28 * d, cy + textLight.textSize / 3f, textLight)
        canvas.drawCircle(tx, cy, btnRadius, white)
        canvas.drawText(ZoomMath.format(ratio), tx, cy + textDark.textSize / 3f, textDark)
    }
}
