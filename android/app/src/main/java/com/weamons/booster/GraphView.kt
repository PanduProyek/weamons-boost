package com.weamons.booster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

// Grafik garis CPU (hijau-teal) dan RAM (ungu) untuk panel game. Skala 0..100%.
class GraphView(ctx: Context) : View(ctx) {

    private val maxPoints = 60
    private val cpu = ArrayList<Int>()
    private val ram = ArrayList<Int>()

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cpuPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ramPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val dens = resources.displayMetrics.density

    init {
        grid.color = Color.parseColor("#55FFFFFF")
        grid.strokeWidth = dens
        label.color = Color.parseColor("#88FFFFFF")
        label.textSize = 8f * dens
        cpuPaint.color = Color.parseColor("#2DD4BF")
        cpuPaint.style = Paint.Style.STROKE
        cpuPaint.strokeWidth = 1.5f * dens
        ramPaint.color = Color.parseColor("#A78BFA")
        ramPaint.style = Paint.Style.STROKE
        ramPaint.strokeWidth = 1.2f * dens
    }

    // cpuVal -1 = tidak tersedia (tidak digambar)
    fun push(cpuVal: Int, ramVal: Int) {
        cpu.add(cpuVal)
        ram.add(ramVal)
        while (cpu.size > maxPoints) cpu.removeAt(0)
        while (ram.size > maxPoints) ram.removeAt(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val left = 22f * dens
        val top = 4f * dens
        val bottom = h - 4f * dens
        val plotH = bottom - top
        val marks = intArrayOf(0, 25, 50, 75)
        for (m in marks) {
            val y = bottom - plotH * m / 100f
            canvas.drawLine(left, y, w, y, grid)
            canvas.drawText("$m%", 0f, y + 3f * dens, label)
        }
        drawSeries(canvas, cpu, cpuPaint, left, w, top, plotH)
        drawSeries(canvas, ram, ramPaint, left, w, top, plotH)
    }

    private fun drawSeries(
        canvas: Canvas, data: List<Int>, p: Paint,
        left: Float, right: Float, top: Float, plotH: Float
    ) {
        if (data.size < 2) return
        path.reset()
        val step = (right - left) / (maxPoints - 1)
        val startX = right - step * (data.size - 1)
        var started = false
        for (i in data.indices) {
            val v = data[i]
            if (v < 0) {
                started = false
                continue
            }
            val x = startX + step * i
            val y = top + plotH - plotH * v.coerceIn(0, 100) / 100f
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, p)
    }
}
