package dev.glowcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Full-screen, non-interactive overlay that draws the focus circle. */
class FocusRing @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
    }
    private var cx = 0f
    private var cy = 0f
    private var shown = false

    init {
        alpha = 0f
        isClickable = false
        isFocusable = false
    }

    fun showAt(x: Float, y: Float) {
        cx = x
        cy = y
        invalidate()
        if (!shown) {
            shown = true
            animate().cancel()
            animate().alpha(1f).setDuration(120).start()
        }
    }

    fun hide() {
        if (!shown) return
        shown = false
        animate().cancel()
        animate().alpha(0f).setDuration(300).start()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, 30 * d, paint)
    }
}
