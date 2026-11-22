package com.weamons.booster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max

// Tampilan crosshair statis di tengah layar. Tidak membaca atau mengubah game apa pun.
class CrosshairView(ctx: Context) : View(ctx) {

    companion object {
        val STYLE_NAMES = arrayOf("Titik", "Plus", "Plus celah", "Lingkaran", "Silang")
        val COLOR_NAMES = arrayOf("Merah", "Hijau", "Cyan", "Kuning", "Putih", "Magenta")
        val COLORS = intArrayOf(
            Color.parseColor("#FF2D2D"),
            Color.parseColor("#22FF22"),
            Color.parseColor("#00E5FF"),
            Color.parseColor("#FFEA00"),
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#FF00FF")
        )
        const val BOX_DP = 160
    }

    var style = 2
    var colorIndex = 0
    var sizeDp = 12
    var thickDp = 2
    var gapDp = 4
    var opacity = 90

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (BOX_DP * resources.displayMetrics.density).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val s = sizeDp * d
        val g = gapDp * d
        val t = max(1f, thickDp * d)
        paint.color = COLORS[colorIndex % COLORS.size]
        paint.alpha = (opacity.coerceIn(0, 100) * 255) / 100
        paint.strokeWidth = t
        paint.strokeCap = Paint.Cap.ROUND

        when (style) {
            0 -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, t * 1.5f, paint)
            }
            1 -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(cx - s, cy, cx + s, cy, paint)
                canvas.drawLine(cx, cy - s, cx, cy + s, paint)
            }
            2 -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(cx - g - s, cy, cx - g, cy, paint)
                canvas.drawLine(cx + g, cy, cx + g + s, cy, paint)
                canvas.drawLine(cx, cy - g - s, cx, cy - g, paint)
                canvas.drawLine(cx, cy + g, cx, cy + g + s, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, t, paint)
            }
            3 -> {
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(cx, cy, s, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, t, paint)
            }
            else -> {
                paint.style = Paint.Style.STROKE
                val k = s * 0.7071f
                val gk = g * 0.7071f
                canvas.drawLine(cx - gk - k, cy - gk - k, cx - gk, cy - gk, paint)
                canvas.drawLine(cx + gk, cy - gk, cx + gk + k, cy - gk - k, paint)
                canvas.drawLine(cx - gk - k, cy + gk + k, cx - gk, cy + gk, paint)
                canvas.drawLine(cx + gk, cy + gk, cx + gk + k, cy + gk + k, paint)
            }
        }
    }
}
