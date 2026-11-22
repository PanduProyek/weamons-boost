package com.weamons.booster

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

// Optimizer: menutup proses latar aplikasi lain yang diizinkan Android, lalu melaporkan RAM kosong.
// Android tidak mengizinkan aplikasi biasa mematikan aplikasi lain secara paksa; hasilnya bervariasi.
object Optimizer {

    private fun availMb(c: Context): Long {
        val am = c.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / (1024L * 1024L)
    }

    fun run(c: Context, keepPkg: String?, done: (before: Long, after: Long) -> Unit) {
        val before = availMb(c)
        val am = c.getSystemService(ActivityManager::class.java)
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = c.packageManager.queryIntentActivities(q, 0)
            .map { it.activityInfo.packageName }
            .toSet()
        for (p in pkgs) {
            if (p != c.packageName && p != keepPkg) {
                try {
                    am.killBackgroundProcesses(p)
                } catch (e: Exception) {
                }
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({ done(before, availMb(c)) }, 1200)
    }
}
