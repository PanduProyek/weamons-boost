package com.weamons.booster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

// Layanan ini dipakai untuk screenshot bawaan sistem, Auto-Boost, dan memutar ketukan makro. Tidak membaca isi layar.
class BoosterAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BoosterAccessibilityService? = null

        fun isEnabled(c: Context): Boolean {
            val s = Settings.Secure.getString(
                c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return s.contains(c.packageName + "/" + BoosterAccessibilityService::class.java.name)
        }

        fun screenshot(): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            val s = instance ?: return false
            return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        }

        // ketukan di koordinat layar (dipakai makro)
        fun tap(x: Float, y: Float, durMs: Long): Boolean {
            if (Build.VERSION.SDK_INT < 24) return false
            val s = instance ?: return false
            val path = Path()
            path.moveTo(x, y)
            val stroke = GestureDescription.StrokeDescription(path, 0L, if (durMs < 1L) 1L else durMs)
            val g = GestureDescription.Builder().addStroke(stroke).build()
            return s.dispatchGesture(g, null, null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private var lastPkg = ""

    // Auto-Boost: hanya membaca nama aplikasi yang sedang di depan, bukan isi layar.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        val changed = pkg != lastPkg
        lastPkg = pkg
        if (!changed || !Prefs.autoBoost(this) || OverlayService.alive) return
        val games = HashSet<String>(Prefs.favs(this))
        Prefs.boostPkg(this)?.let { games.add(it) }
        if (!games.contains(pkg)) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            val i = Intent(this, OverlayService::class.java)
            i.putExtra("pkg", pkg)
            i.putExtra("auto", true)
            startForegroundService(i)
        } catch (e: Exception) {
        }
    }

    override fun onInterrupt() {}
}
