package com.weamons.booster

import android.content.Context

// Makro = daftar ketukan (x, y, lama tekan, jeda sebelum ketukan), disimpan sebagai teks.
object Macro {
    class Step(val x: Int, val y: Int, val dur: Long, val delay: Long)

    fun load(c: Context): List<Step> {
        val raw = Prefs.macroRaw(c)
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Step>()
        for (part in raw.split(";")) {
            val f = part.split(",")
            if (f.size == 4) {
                try {
                    out.add(Step(f[0].toInt(), f[1].toInt(), f[2].toLong(), f[3].toLong()))
                } catch (e: Exception) {
                }
            }
        }
        return out
    }

    fun save(c: Context, steps: List<Step>) {
        Prefs.setMacroRaw(c, steps.joinToString(";") { "${it.x},${it.y},${it.dur},${it.delay}" })
    }

    fun count(c: Context): Int = load(c).size
}
