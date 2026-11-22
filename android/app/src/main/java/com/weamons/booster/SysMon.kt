package com.weamons.booster

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.io.RandomAccessFile

// Pembaca status HP: CPU, RAM, baterai, suhu baterai.
// CPU: Android 8+ memblokir /proc/stat untuk aplikasi biasa di banyak HP. Jika diblokir,
// angka CPU diperkirakan dari rasio frekuensi inti (kalau HP mengizinkan), jika tidak -1.
object SysMon {

    class Snap(
        val cpu: Int,
        val ram: Int,
        val bat: Int,
        val temp: Float,
        val charging: Boolean
    )

    private var statOk = true
    private var lastTotal = 0L
    private var lastIdle = 0L
    private var lastCpu = -1

    fun read(c: Context): Snap {
        val (bat, temp, charging) = battery(c)
        return Snap(cpu(), ram(c), bat, temp, charging)
    }

    fun ram(c: Context): Int {
        val am = c.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem <= 0L) return 0
        return (100L - mi.availMem * 100L / mi.totalMem).toInt().coerceIn(0, 100)
    }

    fun battery(c: Context): Triple<Int, Float, Boolean> {
        val bi: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi == null) return Triple(0, 0f, false)
        val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (scale > 0) level * 100 / scale else level
        return Triple(pct.coerceIn(0, 100), temp, charging)
    }

    @Synchronized
    fun cpu(): Int {
        if (statOk) {
            try {
                RandomAccessFile("/proc/stat", "r").use { f ->
                    val line = f.readLine() ?: throw IllegalStateException("kosong")
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size < 8) throw IllegalStateException("format")
                    var total = 0L
                    for (i in 1..7) total += p[i].toLong()
                    val idle = p[4].toLong() + p[5].toLong()
                    val dTotal = total - lastTotal
                    val dIdle = idle - lastIdle
                    val first = lastTotal == 0L
                    lastTotal = total
                    lastIdle = idle
                    if (!first && dTotal > 0L) {
                        lastCpu = ((dTotal - dIdle) * 100L / dTotal).toInt().coerceIn(0, 100)
                    }
                }
                return lastCpu
            } catch (e: Exception) {
                statOk = false
            }
        }
        return cpuByFreq()
    }

    private fun readLong(f: File): Long =
        try {
            f.readText().trim().toLong()
        } catch (e: Exception) {
            -1L
        }

    private fun cpuByFreq(): Int {
        val base = File("/sys/devices/system/cpu")
        val cores = base.listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) } ?: return -1
        var n = 0
        var sum = 0.0
        for (core in cores) {
            val cur = readLong(File(core, "cpufreq/scaling_cur_freq"))
            val mx = readLong(File(core, "cpufreq/cpuinfo_max_freq"))
            if (cur > 0L && mx > 0L) {
                sum += cur.toDouble() / mx.toDouble()
                n++
            }
        }
        return if (n == 0) -1 else (sum / n * 100.0).toInt().coerceIn(0, 100)
    }
}
