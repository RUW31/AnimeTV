package com.example.animetv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** Puntero tipo ratón que se mueve con el D-pad. Se desvanece tras unos segundos sin usarse. */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var cx = 0f
        private set
    var cy = 0f
        private set

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val arrow = Path()
    private var pulseUntil = 0L
    private val fade = Runnable { animate().alpha(0f).setDuration(400).start() }

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cx == 0f && cy == 0f) {
            cx = w / 2f
            cy = h / 2f
        }
    }

    /** Mueve el cursor y devuelve cuánto se pasó del borde (para hacer scroll). */
    fun moveBy(dx: Float, dy: Float): Pair<Float, Float> {
        val nx = cx + dx
        val ny = cy + dy
        cx = nx.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        cy = ny.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        poke()
        return (nx - cx) to (ny - cy)
    }

    fun center() {
        cx = width / 2f
        cy = height / 2f
        poke()
    }

    /** Hace visible el cursor y reinicia el temporizador de desvanecido. */
    fun poke() {
        animate().cancel()
        alpha = 1f
        removeCallbacks(fade)
        postDelayed(fade, 4_000)
        invalidate()
    }

    /** Anillo breve al hacer clic. */
    fun pulse() {
        pulseUntil = SystemClock.uptimeMillis() + 180
        poke()
        postInvalidateDelayed(200)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = density
        if (SystemClock.uptimeMillis() < pulseUntil) {
            canvas.drawCircle(cx, cy, 16f * s, ring)
        }
        arrow.reset()
        arrow.moveTo(cx, cy)
        arrow.lineTo(cx, cy + 22f * s)
        arrow.lineTo(cx + 6f * s, cy + 16.5f * s)
        arrow.lineTo(cx + 10f * s, cy + 25f * s)
        arrow.lineTo(cx + 13.5f * s, cy + 23.5f * s)
        arrow.lineTo(cx + 9.5f * s, cy + 15f * s)
        arrow.lineTo(cx + 17f * s, cy + 15f * s)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
    }
}
