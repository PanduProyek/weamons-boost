package com.weamons.booster

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

// Layanan ini hanya dipakai untuk memicu screenshot bawaan sistem. Tidak membaca isi layar.
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
