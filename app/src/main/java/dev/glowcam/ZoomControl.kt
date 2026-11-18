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
import kotlin.math.abs

/**
 * Zoom control.
 * - resting: a pill with up to three quick buttons (e.g. `.5  1×  2`); tap one to jump to it
 * - press and hold: the pill widens into a ruler with a bubble showing the ratio; drag to zoom;
 *   release collapses back to the buttons
 */
class ZoomControl @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var minRatio = 1f
    var maxRatio = 1f
    var ratio = 1f
        set(v) {
            field = v
            invalidate()
        }

    /** Called with the ratio of a tapped quick button. */
    var onSelect: ((Float) -> Unit)? = null
    var onRatioChanged: ((Float) -> Unit)? = null

    /** True while the ruler is open (finger down after a long press). */
    var expanded = false
        private set

    private val d = resources.displayMetrics.density
    private val itemW = 48 * d
    private val pillH = 48 * d
    private val circleR = 22 * d
    private val bottomGap = 4 * d
    private val rulerPad = 28 * d
    private val sideMargin = 16 * d

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61C1B1B.toInt() }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCDE5F5.toInt() }
    private val textLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 15 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val textDark = Paint(textLight).apply { color = 0xFF1F3A4D.toInt() }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA9CCE3.toInt(); strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA9CCE3.toInt(); textSize = 12 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private var downInside = false
    private var longPressed = false
    private var downItem = -1
    private var lastX = 0f
    private var anchorX = 0f
    private var anchorFraction = 0f

    private val longPress = Runnable {
        if (downInside) {
            longPressed = true
            expanded = true
            anchorX = lastX
            anchorFraction = ZoomMath.fractionFromRatio(ratio, minRatio, maxRatio)
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private fun supportsZoom() = maxRatio - minRatio > 0.05f

    private fun presets() = ZoomMath.presets(minRatio, maxRatio)

    private fun pillCenterY() = height - bottomGap - pillH / 2f

    private fun collapsedPill(n: Int): RectF {
        val w = n * itemW // no side padding: the selected circle then sits 2dp from every pill edge
        val cx = width / 2f
        val cy = pillCenterY()
        return RectF(cx - w / 2, cy - pillH / 2, cx + w / 2, cy + pillH / 2)
    }

    private fun expandedPill(): RectF {
        val cy = pillCenterY()
        return RectF(sideMargin, cy - pillH / 2, width - sideMargin, cy + pillH / 2)
    }

    private fun rulerLeft() = expandedPill().left + rulerPad
    private fun rulerRight() = expandedPill().right - rulerPad

    private fun xForFraction(f: Float) = rulerLeft() + f * (rulerRight() - rulerLeft())

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!supportsZoom()) return false
        lastX = e.x
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ps = presets()
                val pill = collapsedPill(ps.size)
                if (!pill.contains(e.x, e.y)) return false
                downInside = true
                longPressed = false
                downItem = ((e.x - pill.left) / itemW).toInt().coerceIn(0, ps.size - 1)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> if (expanded) {
                val f = ZoomMath.dragFraction(e.x, anchorX, anchorFraction, rulerLeft(), rulerRight())
                val r = ZoomMath.ratioFromFraction(f, minRatio, maxRatio)
                ratio = r
                onRatioChanged?.invoke(r)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val wasLong = longPressed
                collapse()
                if (!wasLong && downInside && downItem >= 0) {
                    performClick()
                    val target = presets().getOrNull(downItem)
                    if (target != null) {
                        ratio = target
                        onSelect?.invoke(target)
                    }
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

    override fun onDraw(canvas: Canvas) {
        if (!supportsZoom()) return
        if (expanded) drawRuler(canvas) else drawButtons(canvas)
    }

    private fun drawButtons(canvas: Canvas) {
        val ps = presets()
        val pill = collapsedPill(ps.size)
        canvas.drawRoundRect(pill, pillH / 2, pillH / 2, pillPaint)
        val sel = ZoomMath.selectedIndex(ps, ratio)
        val cy = pillCenterY()
        for (i in ps.indices) {
            val cx = pill.left + itemW * i + itemW / 2
            if (i == sel) {
                canvas.drawCircle(cx, cy, circleR, selPaint)
                canvas.drawText(ZoomMath.format(ratio), cx, cy + textDark.textSize / 3f, textDark)
            } else {
                canvas.drawText(ZoomMath.plain(ps[i]), cx, cy + textLight.textSize / 3f, textLight)
            }
        }
    }

    private fun drawRuler(canvas: Canvas) {
        val pill = expandedPill()
        canvas.drawRoundRect(pill, pillH / 2, pillH / 2, pillPaint)
        val cy = pillCenterY()
        val tickY = cy - 6 * d

        // Labelled (tall) positions: min, 1x, 2x, max (when distinct and in range).
        val labelled = listOf(minRatio, 1f, 2f, maxRatio)
            .filter { it in minRatio..maxRatio }
            .fold(ArrayList<Float>()) { acc, r ->
                if (acc.none { abs(ZoomMath.fractionFromRatio(it, minRatio, maxRatio) - ZoomMath.fractionFromRatio(r, minRatio, maxRatio)) < 0.06f }) acc.add(r)
                acc
            }
        val labelledFractions = labelled.map { ZoomMath.fractionFromRatio(it, minRatio, maxRatio) }

        val steps = 24
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            if (labelledFractions.any { abs(it - f) < 0.02f }) continue
            val x = xForFraction(f)
            canvas.drawLine(x, tickY - 5 * d, x, tickY + 5 * d, tickPaint)
        }
        for ((i, r) in labelled.withIndex()) {
            val x = xForFraction(labelledFractions[i])
            canvas.drawLine(x, tickY - 9 * d, x, tickY + 9 * d, tickPaint)
            canvas.drawText(ZoomMath.plain(r), x, cy + 20 * d, labelPaint)
        }

        // Current position: highlighted tick + bubble.
        val f = ZoomMath.fractionFromRatio(ratio, minRatio, maxRatio)
        val tx = xForFraction(f)
        val thumb = Paint(selPaint).apply { strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(tx, tickY - 12 * d, tx, tickY + 12 * d, thumb)

        val bw = 64 * d
        val bh = 34 * d
        val bx = tx.coerceIn(pill.left + bw / 2, pill.right - bw / 2)
        val top = pill.top - 8 * d - bh
        canvas.drawRoundRect(RectF(bx - bw / 2, top, bx + bw / 2, top + bh), bh / 2, bh / 2, selPaint)
        canvas.drawText(ZoomMath.format(ratio), bx, top + bh / 2 + textDark.textSize / 3f, textDark)
    }
}
