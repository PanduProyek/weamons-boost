package com.weamons.booster

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("weamons", Context.MODE_PRIVATE)

    fun side(c: Context): Int = sp(c).getInt("side", 0)
    fun setSide(c: Context, v: Int) {
        sp(c).edit().putInt("side", v).apply()
    }

    fun barY(c: Context): Int = sp(c).getInt("barY", -1)
    fun setBarY(c: Context, v: Int) {
        sp(c).edit().putInt("barY", v).apply()
    }

    // posisi vertikal bilah sebagai pecahan tinggi layar (0..1), aman saat layar diputar
    fun barFrac(c: Context): Float = sp(c).getFloat("barFrac", 0.5f)
    fun setBarFrac(c: Context, v: Float) {
        sp(c).edit().putFloat("barFrac", v).apply()
    }

    // ---------- makro ----------
    fun macroRaw(c: Context): String = sp(c).getString("macroRaw", "") ?: ""
    fun setMacroRaw(c: Context, v: String) {
        sp(c).edit().putString("macroRaw", v).apply()
    }

    // jeda antar putaran makro (ms)
    fun macroLoopMs(c: Context): Int = sp(c).getInt("macroLoopMs", 1000)
    fun setMacroLoopMs(c: Context, v: Int) {
        sp(c).edit().putInt("macroLoopMs", v).apply()
    }

    // ---------- crosshair ----------
    fun crossOn(c: Context): Boolean = sp(c).getBoolean("crossOn", false)
    fun setCrossOn(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("crossOn", v).apply()
    }

    fun crossInt(c: Context, key: String, def: Int): Int = sp(c).getInt("cross_" + key, def)
    fun setCrossInt(c: Context, key: String, v: Int) {
        sp(c).edit().putInt("cross_" + key, v).apply()
    }

    // 0 = kecil, 1 = sedang, 2 = besar
    fun scale(c: Context): Int = sp(c).getInt("scale", 1)
    fun setScale(c: Context, v: Int) {
        sp(c).edit().putInt("scale", v).apply()
    }

    fun scaleFactor(c: Context): Float = when (scale(c)) {
        0 -> 0.65f
        2 -> 1.0f
        else -> 0.8f
    }

    fun alpha(c: Context): Int = sp(c).getInt("alpha", 90)
    fun setAlpha(c: Context, v: Int) {
        sp(c).edit().putInt("alpha", v).apply()
    }

    fun breakMin(c: Context): Int = sp(c).getInt("breakMin", 0)
    fun setBreakMin(c: Context, v: Int) {
        sp(c).edit().putInt("breakMin", v).apply()
    }

    fun autoDnd(c: Context): Boolean = sp(c).getBoolean("autoDnd", false)
    fun setAutoDnd(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("autoDnd", v).apply()
    }

    fun hud(c: Context): Boolean = sp(c).getBoolean("hud", true)
    fun setHud(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("hud", v).apply()
    }

    fun tempAlert(c: Context): Boolean = sp(c).getBoolean("tempAlert", true)
    fun setTempAlert(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("tempAlert", v).apply()
    }

    // profil otomatis saat game mulai: -1 = tidak ada, 0 = hemat, 1 = seimbang, 2 = pro
    fun startProfile(c: Context): Int = sp(c).getInt("startProfile", -1)
    fun setStartProfile(c: Context, v: Int) {
        sp(c).edit().putInt("startProfile", v).apply()
    }

    fun pingHost(c: Context): Int = sp(c).getInt("pingHost", 0)
    fun setPingHost(c: Context, v: Int) {
        sp(c).edit().putInt("pingHost", v).apply()
    }

    // ---------- umum + layar Booster ----------
    fun getInt(c: Context, key: String, def: Int): Int = sp(c).getInt(key, def)
    fun setInt(c: Context, key: String, v: Int) {
        sp(c).edit().putInt(key, v).apply()
    }

    fun autoBoost(c: Context): Boolean = sp(c).getBoolean("autoBoost", false)
    fun setAutoBoost(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("autoBoost", v).apply()
    }

    fun meterOn(c: Context): Boolean = sp(c).getBoolean("meterOn", false)
    fun setMeterOn(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("meterOn", v).apply()
    }

    // game yang dipilih di layar Booster
    fun boostPkg(c: Context): String? = sp(c).getString("boostPkg", null)
    fun setBoostPkg(c: Context, v: String) {
        sp(c).edit().putString("boostPkg", v).apply()
    }

    fun favs(c: Context): Set<String> {
        val s = sp(c).getStringSet("favs", null)
        return if (s == null) emptySet() else HashSet<String>(s)
    }

    fun toggleFav(c: Context, pkg: String) {
        val s = HashSet<String>(favs(c))
        if (!s.add(pkg)) s.remove(pkg)
        sp(c).edit().putStringSet("favs", s).apply()
    }

    private fun dayKey(d: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)

    fun addPlay(c: Context, pkg: String, sec: Long) {
        if (sec <= 0L) return
        val k = "stat_" + dayKey(Date()) + "_" + pkg
        val cur = sp(c).getLong(k, 0L)
        sp(c).edit().putLong(k, cur + sec).apply()
    }

    fun todayStats(c: Context): List<Pair<String, Long>> {
        val prefix = "stat_" + dayKey(Date()) + "_"
        val out = ArrayList<Pair<String, Long>>()
        for ((k, v) in sp(c).all) {
            if (k.startsWith(prefix) && v is Long) {
                out.add(Pair(k.substring(prefix.length), v))
            }
        }
        return out.sortedByDescending { it.second }
    }

    fun periodSeconds(c: Context, days: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val cutoff = dayKey(cal.time)
        var total = 0L
        for ((k, v) in sp(c).all) {
            if (k.startsWith("stat_") && k.length > 15 && v is Long) {
                if (k.substring(5, 15) >= cutoff) total += v
            }
        }
        return total
    }

    fun fmt(sec: Long): String {
        val h = sec / 3600
        val m = (sec / 60) % 60
        return if (h > 0) "${h}j ${m}m" else "${m}m"
    }
}
