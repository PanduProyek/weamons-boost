package com.weamons.booster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import java.util.Random

// Latar layar Booster: gradasi gelap, bintang, dan bingkai segi enam biru di atas.
class HudFrameView(ctx: Context) : View(ctx) {
    private val dens = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tree = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val stars = ArrayList<FloatArray>()

    init {
        line.style = Paint.Style.STROKE
        line.strokeWidth = 1.5f * dens
        line.color = Color.parseColor("#2F7DD1")
        star.color = Color.WHITE
        tree.color = Color.parseColor("#0A0F14")
        val r = Random(7)
        for (i in 0 until 60) {
            stars.add(floatArrayOf(r.nextFloat(), r.nextFloat() * 0.7f, 0.5f + r.nextFloat() * 1.3f))
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bg.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#02030A"), Color.parseColor("#06122A"), Color.parseColor("#02070F")),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, bg)
        for (s in stars) {
            star.alpha = 80 + (s[2] * 60).toInt()
            canvas.drawCircle(s[0] * w, s[1] * h, s[2] * dens * 0.8f, star)
        }
        // siluet pohon di bawah
        path.reset()
        path.moveTo(w * 0.28f, h)
        var x = w * 0.28f
        var up = true
        while (x < w * 0.72f) {
            val nx = x + 14f * dens
            path.lineTo(x + 7f * dens, h - (if (up) 26f else 14f) * dens)
            path.lineTo(nx, h)
            x = nx
            up = !up
        }
        path.lineTo(w * 0.72f, h)
        path.close()
        canvas.drawPath(path, tree)
        // bingkai: sisi miring kiri/kanan atas dan bawah
        path.reset()
        path.moveTo(w * 0.10f, 0f)
        path.lineTo(w * 0.28f, h * 0.17f)
        path.lineTo(w * 0.72f, h * 0.17f)
        path.lineTo(w * 0.90f, 0f)
        canvas.drawPath(path, line)
        path.reset()
        path.moveTo(w * 0.10f, h)
        path.lineTo(w * 0.28f, h * 0.83f)
        path.lineTo(w * 0.72f, h * 0.83f)
        path.lineTo(w * 0.90f, h)
        canvas.drawPath(path, line)
    }
}

// Tombol BOOST berbentuk trapesium biru.
class BoostButtonView(ctx: Context) : View(ctx) {
    private val dens = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    var label = "BOOST"
        set(v) {
            field = v
            invalidate()
        }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    init {
        fill.color = Color.parseColor("#0A96FF")
        text.color = Color.WHITE
        text.textSize = 20f * dens
        text.typeface = android.graphics.Typeface.DEFAULT_BOLD
        text.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0.08f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = h * 0.55f
        path.reset()
        path.moveTo(inset, 0f)
        path.lineTo(w - inset, 0f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        fill.color = if (isPressed) Color.parseColor("#0572C4") else Color.parseColor("#0A96FF")
        canvas.drawPath(path, fill)
        canvas.drawText(label, w / 2f, h / 2f + text.textSize * 0.35f, text)
    }
}

// Slider vertikal bulat seperti di Game Booster: isi naik dari bawah, ikon di dasar.
class VSlider(ctx: Context, private val icon: String) : View(ctx) {
    private val dens = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    var max = 100
    var value = 0
        set(v) {
            field = v.coerceIn(0, max)
            invalidate()
        }
    var tracking = false
        private set
    var onChange: ((Int) -> Unit)? = null
    var onLocked: (() -> Unit)? = null
    var locked = false

    init {
        track.color = Color.parseColor("#262629")
        fill.color = Color.parseColor("#E8E8EA")
        glyph.textSize = 16f * dens
        glyph.textAlign = Paint.Align.CENTER
        glyph.color = Color.parseColor("#1A1A1C")
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val fh = h * value / max
        if (fh > 0f) {
            canvas.save()
            val clip = Path()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.clipPath(clip)
            rect.set(0f, h - fh, w, h)
            canvas.drawRect(rect, fill)
            canvas.restore()
        }
        glyph.color = if (fh > w) Color.parseColor("#1A1A1C") else Color.parseColor("#CFCFD4")
        canvas.drawText(icon, w / 2f, h - w * 0.33f, glyph)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (locked) {
            if (e.action == MotionEvent.ACTION_DOWN) onLocked?.invoke()
            return true
        }
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                set(e)
            }
            MotionEvent.ACTION_MOVE -> set(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    }

    private fun set(e: MotionEvent) {
        val p = ((height - e.y) / height * max).toInt().coerceIn(0, max)
        value = p
        onChange?.invoke(p)
    }
}
