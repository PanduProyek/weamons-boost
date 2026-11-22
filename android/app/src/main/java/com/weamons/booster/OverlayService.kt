package com.weamons.booster

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: LinearLayout? = null
    private lateinit var panel: LinearLayout
    private lateinit var clockTv: TextView
    private lateinit var sessionTv: TextView
    private lateinit var batteryTv: TextView
    private lateinit var ramTv: TextView
    private lateinit var dndBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var startTime = 0L
    private var gamePkg: String? = null
    private var dndOn = false
    private var prevFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    private val ticker = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        gamePkg = intent?.getStringExtra("pkg")
        startForeground(1, buildNotification())
        if (root == null) {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            startTime = System.currentTimeMillis()
            buildOverlay()
            handler.post(ticker)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("overlay", "Bilah game", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "overlay")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("WEAMONS BOOST aktif")
            .setContentText("Bilah game sedang berjalan")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun txt(size: Float, color: String): TextView {
        val t = TextView(this)
        t.textSize = size
        t.setTextColor(Color.parseColor(color))
        return t
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 12f
        b.minHeight = 0
        b.minimumHeight = 0
        b.setTextColor(Color.WHITE)
        val shape = GradientDrawable()
        shape.setColor(Color.parseColor("#902DE0"))
        shape.cornerRadius = dp(10).toFloat()
        b.background = shape
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
        lp.topMargin = dp(6)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildOverlay() {
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(12), dp(12), dp(12), dp(12))
        val panelBg = GradientDrawable()
        panelBg.setColor(Color.parseColor("#F2211831"))
        panelBg.cornerRadius = dp(14).toFloat()
        panel.background = panelBg
        panel.visibility = View.GONE

        clockTv = txt(20f, "#C9A2FF")
        sessionTv = txt(13f, "#FFFFFF")
        batteryTv = txt(13f, "#FFFFFF")
        ramTv = txt(13f, "#FFFFFF")
        panel.addView(clockTv)
        panel.addView(sessionTv)
        panel.addView(batteryTv)
        panel.addView(ramTv)
        dndBtn = btn("Jangan Ganggu: OFF") { toggleDnd() }
        panel.addView(dndBtn)
        panel.addView(btn("Bersihkan RAM") { cleanRam() })
        panel.addView(btn("Tutup bilah") { stopSelf() })

        val handle = View(this)
        val handleBg = GradientDrawable()
        handleBg.setColor(Color.parseColor("#CC902DE0"))
        val r8 = dp(8).toFloat()
        handleBg.cornerRadii = floatArrayOf(0f, 0f, r8, r8, r8, r8, 0f, 0f)
        handle.background = handleBg

        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(panel, LinearLayout.LayoutParams(dp(190), LinearLayout.LayoutParams.WRAP_CONTENT))
        r.addView(handle, LinearLayout.LayoutParams(dp(24), dp(84)))
        root = r

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        params.x = 0
        params.y = 0

        handle.setOnTouchListener(object : View.OnTouchListener {
            private var downY = 0f
            private var startOffset = 0
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY
                        startOffset = params.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val d = (e.rawY - downY).toInt()
                        if (abs(d) > dp(8)) moved = true
                        if (moved) {
                            params.y = startOffset + d
                            wm.updateViewLayout(r, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            panel.visibility =
                                if (panel.visibility == View.GONE) View.VISIBLE else View.GONE
                        }
                    }
                }
                return true
            }
        })

        wm.addView(r, params)
        updateStats()
    }

    private fun availMb(): Long {
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / (1024L * 1024L)
    }

    private fun updateStats() {
        clockTv.text = timeFmt.format(Date())
        val sec = (System.currentTimeMillis() - startTime) / 1000
        sessionTv.text = String.format(Locale.US, "Main: %02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)

        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (scale > 0) level * 100 / scale else level
            val bolt = if (charging) " +" else ""
            batteryTv.text = String.format(Locale.US, "Baterai: %d%%%s  %.1f C", pct, bolt, temp)
        }
        ramTv.text = "RAM kosong: " + availMb() + " MB"
    }

    private fun toggleDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            Toast.makeText(this, "Beri izin Jangan Ganggu untuk WEAMONS dulu", Toast.LENGTH_LONG).show()
            val i = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            return
        }
        if (!dndOn) {
            prevFilter = nm.currentInterruptionFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            dndOn = true
        } else {
            restoreDnd(nm)
        }
        dndBtn.text = if (dndOn) "Jangan Ganggu: ON" else "Jangan Ganggu: OFF"
    }

    private fun restoreDnd(nm: NotificationManager) {
        val f = if (prevFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
            NotificationManager.INTERRUPTION_FILTER_ALL else prevFilter
        nm.setInterruptionFilter(f)
        dndOn = false
    }

    private fun cleanRam() {
        val am = getSystemService(ActivityManager::class.java)
        val before = availMb()
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = packageManager.queryIntentActivities(query, 0)
            .map { it.activityInfo.packageName }
            .toSet()
        for (p in pkgs) {
            if (p != packageName && p != gamePkg) {
                am.killBackgroundProcesses(p)
            }
        }
        handler.postDelayed({
            val after = availMb()
            Toast.makeText(this, "RAM kosong: $before MB menjadi $after MB", Toast.LENGTH_LONG).show()
        }, 1200)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (dndOn) {
            try {
                restoreDnd(getSystemService(NotificationManager::class.java))
            } catch (e: Exception) {
            }
        }
        root?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        root = null
        super.onDestroy()
    }
}
