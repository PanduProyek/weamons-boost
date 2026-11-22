package com.weamons.booster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

// Meter kecil melayang: CPU, RAM, BAT, TEMP, dan HZ (laju segar layar).
// Catatan: HZ bukan FPS game. Membaca FPS game lain butuh akses sistem (root/Shizuku).
class MeterOverlay(private val ctx: Context, private val wm: WindowManager) {

    private val view = MeterView(ctx)
    private var added = false
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    init {
        params.gravity = Gravity.TOP or Gravity.START
        params.x = Prefs.getInt(ctx, "meterX", (60 * ctx.resources.displayMetrics.density).toInt())
        params.y = Prefs.getInt(ctx, "meterY", (24 * ctx.resources.displayMetrics.density).toInt())
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (moved || abs(dx) > 6 || abs(dy) > 6) {
                            moved = true
                            params.x = (startX + dx).coerceAtLeast(0)
                            params.y = (startY + dy).coerceAtLeast(0)
                            try {
                                wm.updateViewLayout(view, params)
                            } catch (ex: Exception) {
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            Prefs.setInt(ctx, "meterX", params.x)
                            Prefs.setInt(ctx, "meterY", params.y)
                        }
                    }
                }
                return true
            }
        })
    }

    val isShown: Boolean get() = added

    fun show() {
        if (added) return
        try {
            wm.addView(view, params)
            added = true
        } catch (e: Exception) {
        }
    }

    fun hide() {
        if (!added) return
        try {
            wm.removeView(view)
        } catch (e: Exception) {
        }
        added = false
    }

    fun update(s: SysMon.Snap) {
        if (!added) return
        @Suppress("DEPRECATION")
        val hz = wm.defaultDisplay.refreshRate
        view.set(s, hz)
    }

    private class MeterView(c: Context) : View(c) {
        private val dens = resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cap = Paint(Paint.ANTI_ALIAS_FLAG)
        private val value = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barBg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        private val caps = arrayOf("CPU", "RAM", "BAT", "TEMP", "HZ")
        private var vals = arrayOf("--", "--", "--", "--", "--")
        private var pcts = intArrayOf(0, 0, 0, 0, 0)

        init {
            bg.color = Color.parseColor("#CC0B0B12")
            border.color = Color.parseColor("#88FFFFFF")
            border.style = Paint.Style.STROKE
            border.strokeWidth = dens
            cap.color = Color.WHITE
            cap.textSize = 9f * dens
            cap.typeface = android.graphics.Typeface.DEFAULT_BOLD
            value.color = Color.parseColor("#9BE7FF")
            value.textSize = 10f * dens
            barBg.color = Color.parseColor("#33FFFFFF")
            bar.color = Color.parseColor("#38BDF8")
        }

        fun set(s: SysMon.Snap, hz: Float) {
            vals = arrayOf(
                if (s.cpu >= 0) "${s.cpu}%" else "--",
                "${s.ram}%",
                "${s.bat}%",
                String.format(java.util.Locale.US, "%.0f°C", s.temp),
                String.format(java.util.Locale.US, "%.0f", hz)
            )
            pcts = intArrayOf(
                s.cpu.coerceAtLeast(0), s.ram, s.bat,
                (s.temp * 100f / 60f).toInt().coerceIn(0, 100),
                (hz * 100f / 144f).toInt().coerceIn(0, 100)
            )
            invalidate()
        }

        private val cellW get() = 46f * dens

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                (cellW * caps.size + 8f * dens).toInt(),
                (40f * dens).toInt()
            )
        }

        override fun onDraw(canvas: Canvas) {
            rect.set(1f, 1f, width - 1f, height - 1f)
            canvas.drawRoundRect(rect, 4f * dens, 4f * dens, bg)
            canvas.drawRoundRect(rect, 4f * dens, 4f * dens, border)
            for (i in caps.indices) {
                val x = 4f * dens + cellW * i + 4f * dens
                canvas.drawText(caps[i], x, 13f * dens, cap)
                canvas.drawText(vals[i], x, 26f * dens, value)
                val bw = cellW - 12f * dens
                rect.set(x, 31f * dens, x + bw, 34f * dens)
                canvas.drawRect(rect, barBg)
                rect.set(x, 31f * dens, x + bw * pcts[i] / 100f, 34f * dens)
                bar.color = if (pcts[i] >= 85 && i != 2) Color.parseColor("#F87171")
                else Color.parseColor("#38BDF8")
                canvas.drawRect(rect, bar)
            }
        }
    }
}
