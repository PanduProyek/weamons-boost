package com.weamons.booster

import android.content.Context

// Makro = daftar langkah. Langkah = ketukan atau geseran (x2,y2 >= 0), lama tekan, jeda sebelum langkah.
object Macro {
    class Step(
        val x: Int,
        val y: Int,
        val dur: Long,
        val delay: Long,
        val x2: Int = -1,
        val y2: Int = -1
    ) {
        val isDrag: Boolean get() = x2 >= 0 && y2 >= 0
    }

    fun load(c: Context): List<Step> {
        val raw = Prefs.macroRaw(c)
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Step>()
        for (part in raw.split(";")) {
            val f = part.split(",")
            try {
                if (f.size == 4) {
                    out.add(Step(f[0].toInt(), f[1].toInt(), f[2].toLong(), f[3].toLong()))
                } else if (f.size == 6) {
                    out.add(
                        Step(f[0].toInt(), f[1].toInt(), f[2].toLong(), f[3].toLong(), f[4].toInt(), f[5].toInt())
                    )
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    fun save(c: Context, steps: List<Step>) {
        Prefs.setMacroRaw(
            c,
            steps.joinToString(";") { "${it.x},${it.y},${it.dur},${it.delay},${it.x2},${it.y2}" }
        )
    }

    fun count(c: Context): Int = load(c).size
}
