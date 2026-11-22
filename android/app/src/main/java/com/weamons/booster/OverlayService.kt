package com.weamons.booster

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: LinearLayout? = null
    private lateinit var panel: LinearLayout
    private lateinit var handleTv: TextView
    private lateinit var handleBg: GradientDrawable
    private lateinit var clockTv: TextView
    private lateinit var sessionTv: TextView
    private lateinit var batteryTv: TextView
    private lateinit var ramTv: TextView
    private lateinit var pingTv: TextView
    private lateinit var timerTv: TextView
    private lateinit var dndBtn: Button
    private lateinit var recBtn: Button
    private lateinit var volBar: SeekBar
    private lateinit var brightBar: SeekBar
    private lateinit var brightBtn: Button
    private lateinit var audio: AudioManager
    private val profBtns = ArrayList<Button>()

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val hosts = arrayOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
    private val profNames = arrayOf("Hemat", "Seimbang", "Pro")

    private var startTime = 0L
    private var gamePkg: String? = null
    private var dndOn = false
    private var prevFilter = NotificationManager.INTERRUPTION_FILTER_ALL
    private var sc = 0.8f
    private var dockSide = 0
    private var barY = 0
    private var volTracking = false
    private var brightTracking = false
    private var breakMin = 0
    private var breakDone = 0
    private var timerEnd = 0L
    private var hotWarned = false
    private var lowWarned = false
    private var popupHinted = false

    // profil performa
    private var profile = -1
    private var origBright = 128
    private var origMode = 0
    private var origSync = true

    @Volatile
    private var running = false

    @Volatile
    private var lastPing = -1

    @Volatile
    private var pingDelay = 4000L
    private var pingThread: Thread? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, buildNotification())

        val newPkg = intent?.getStringExtra("pkg")
        if (root != null) {
            if (newPkg != null && newPkg != gamePkg) {
                flushStats()
                startTime = System.currentTimeMillis()
                breakDone = 0
                gamePkg = newPkg
            }
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        gamePkg = newPkg
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        audio = getSystemService(AudioManager::class.java)
        sc = Prefs.scaleFactor(this)
        dockSide = Prefs.side(this)
        breakMin = Prefs.breakMin(this)
        startTime = System.currentTimeMillis()
        buildOverlay()
        if (Prefs.autoDnd(this)) setDnd(true)
        val sp = Prefs.startProfile(this)
        if (sp in 0..2) applyProfile(sp)
        startPing()
        handler.post(ticker)
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
        val stopIntent = Intent(this, OverlayService::class.java)
        stopIntent.action = "STOP"
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_launcher), "Hentikan", stopPi
        ).build()
        return Notification.Builder(this, "overlay")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("WEAMONS BOOST aktif")
            .setContentText("Bilah game sedang berjalan")
            .setContentIntent(open)
            .addAction(action)
            .setOngoing(true)
            .build()
    }

    // ---------- ukuran (dikalikan skala) ----------
    private fun d(v: Float): Int = (v * resources.displayMetrics.density * sc).toInt()

    private fun txt(size: Float, color: String): TextView {
        val t = TextView(this)
        t.textSize = size * sc
        t.setTextColor(Color.parseColor(color))
        return t
    }

    private fun tint(b: Button, color: String) {
        val shape = GradientDrawable()
        shape.setColor(Color.parseColor(color))
        shape.cornerRadius = d(8f).toFloat()
        b.background = shape
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 11f * sc
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(d(4f), 0, d(4f), 0)
        b.setTextColor(Color.WHITE)
        tint(b, "#902DE0")
        b.setOnClickListener { onClick() }
        return b
    }

    private fun rowLp(weight: Float): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, d(32f), weight)
        lp.setMargins(d(2f), d(4f), d(2f), 0)
        return lp
    }

    private fun screenW(): Int {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels
    }

    private fun screenH(): Int {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.heightPixels
    }

    private fun clampY(y: Int): Int {
        val r = root
        val h = if (r != null && r.height > 0) r.height else d(72f)
        return y.coerceIn(0, max(0, screenH() - h))
    }

    // ---------- membangun bilah ----------
    private fun buildOverlay() {
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(d(10f), d(8f), d(10f), d(8f))
        val panelBg = GradientDrawable()
        panelBg.setColor(Color.parseColor("#F2211831"))
        panelBg.cornerRadius = d(12f).toFloat()
        panel.background = panelBg
        panel.visibility = View.GONE

        clockTv = txt(18f, "#C9A2FF")
        sessionTv = txt(12f, "#FFFFFF")
        sessionTv.setPadding(d(10f), 0, 0, 0)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(clockTv)
        head.addView(sessionTv)
        panel.addView(head)

        batteryTv = txt(12f, "#FFFFFF")
        ramTv = txt(12f, "#FFFFFF")
        pingTv = txt(12f, "#FFFFFF")
        pingTv.setOnClickListener {
            Prefs.setPingHost(this, (Prefs.pingHost(this) + 1) % hosts.size)
            lastPing = -1
        }
        panel.addView(batteryTv)
        panel.addView(ramTv)
        panel.addView(pingTv)

        // timer cepat
        timerTv = txt(12f, "#FFFFFF")
        timerTv.setOnClickListener { timerEnd = 0L }
        val timerRow = LinearLayout(this)
        timerRow.orientation = LinearLayout.HORIZONTAL
        timerRow.gravity = Gravity.CENTER_VERTICAL
        timerRow.addView(timerTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        timerRow.addView(btn("5m") { startTimer(5) }, rowLp(1f))
        timerRow.addView(btn("10m") { startTimer(10) }, rowLp(1f))
        timerRow.addView(btn("15m") { startTimer(15) }, rowLp(1f))
        panel.addView(timerRow)

        // volume
        volBar = SeekBar(this)
        volBar.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volBar.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                    } catch (e: Exception) {
                    }
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                volTracking = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                volTracking = false
            }
        })
        val volRow = LinearLayout(this)
        volRow.orientation = LinearLayout.HORIZONTAL
        volRow.gravity = Gravity.CENTER_VERTICAL
        val volLabel = txt(11f, "#A99CC6")
        volLabel.text = "Suara"
        volRow.addView(volLabel, LinearLayout.LayoutParams(d(44f), LinearLayout.LayoutParams.WRAP_CONTENT))
        volRow.addView(volBar, LinearLayout.LayoutParams(0, d(30f), 1f))
        panel.addView(volRow)

        // kecerahan
        brightBar = SeekBar(this)
        brightBar.max = 255
        brightBar.progress = currentBrightness()
        brightBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) applyBrightness(max(p, 10))
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                brightTracking = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                brightTracking = false
            }
        })
        brightBtn = btn("Izinkan atur kecerahan") {
            val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
        val brightRow = LinearLayout(this)
        brightRow.orientation = LinearLayout.HORIZONTAL
        brightRow.gravity = Gravity.CENTER_VERTICAL
        val brightLabel = txt(11f, "#A99CC6")
        brightLabel.text = "Terang"
        brightRow.addView(brightLabel, LinearLayout.LayoutParams(d(44f), LinearLayout.LayoutParams.WRAP_CONTENT))
        brightRow.addView(brightBar, LinearLayout.LayoutParams(0, d(30f), 1f))
        brightRow.addView(brightBtn, LinearLayout.LayoutParams(0, d(32f), 1f))
        panel.addView(brightRow)

        // profil performa
        val profLabel = txt(11f, "#A99CC6")
        profLabel.text = "Profil performa"
        profLabel.setPadding(0, d(4f), 0, 0)
        panel.addView(profLabel)
        val profRow = LinearLayout(this)
        profRow.orientation = LinearLayout.HORIZONTAL
        for (i in 0..2) {
            val b = btn(profNames[i]) { applyProfile(i) }
            profBtns.add(b)
            profRow.addView(b, rowLp(1f))
        }
        panel.addView(profRow)

        // aksi cepat 1: screenshot, rekam, bersihkan RAM
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(btn("Screenshot") { takeShot() }, rowLp(1.3f))
        recBtn = btn("Rekam") { toggleRecord() }
        row1.addView(recBtn, rowLp(1f))
        row1.addView(btn("Bersih RAM") { cleanRam() }, rowLp(1.2f))
        panel.addView(row1)

        // aksi cepat 2: DND, chat pop-up, tutup
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        dndBtn = btn("DND: OFF") { toggleDnd() }
        row2.addView(dndBtn, rowLp(1.2f))
        val chats = arrayOf(
            Pair("com.whatsapp", "WA"),
            Pair("com.whatsapp.w4b", "WA Biz"),
            Pair("com.discord", "Discord"),
            Pair("org.telegram.messenger", "Telegram")
        )
        var count = 0
        for (c in chats) {
            if (count < 2 && packageManager.getLaunchIntentForPackage(c.first) != null) {
                row2.addView(btn(c.second) { openChat(c.first) }, rowLp(1f))
                count++
            }
        }
        row2.addView(btn("Tutup") { stopSelf() }, rowLp(0.9f))
        panel.addView(row2)

        handleTv = TextView(this)
        handleTv.textSize = 10f * sc
        handleTv.setTextColor(Color.WHITE)
        handleTv.gravity = Gravity.CENTER
        handleTv.setTypeface(null, android.graphics.Typeface.BOLD)
        handleBg = GradientDrawable()
        handleBg.setColor(Color.parseColor("#CC902DE0"))
        handleTv.background = handleBg

        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.alpha = Prefs.alpha(this) / 100f
        root = r

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        val savedY = Prefs.barY(this)
        barY = if (savedY < 0) screenH() / 2 - d(36f) else savedY
        params.y = clampY(barY)

        handleTv.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        val loc = IntArray(2)
                        r.getLocationOnScreen(loc)
                        startX = loc[0]
                        startY = loc[1]
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (!moved && (abs(dx) > d(6f) || abs(dy) > d(6f))) {
                            moved = true
                            params.gravity = Gravity.TOP or Gravity.START
                        }
                        if (moved) {
                            val maxX = max(0, screenW() - r.width)
                            val maxY = max(0, screenH() - r.height)
                            params.x = (startX + dx).coerceIn(0, maxX)
                            params.y = (startY + dy).coerceIn(0, maxY)
                            wm.updateViewLayout(r, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            val cx = params.x + r.width / 2
                            dockSide = if (cx < screenW() / 2) 0 else 1
                            barY = params.y
                            Prefs.setSide(this@OverlayService, dockSide)
                            Prefs.setBarY(this@OverlayService, barY)
                            handler.post { layoutDock() }
                        } else {
                            togglePanel()
                        }
                    }
                }
                return true
            }
        })

        fillRoot()
        wm.addView(r, params)
        updateProfileButtons()
        updateStats()
    }

    // isi root sesuai sisi: kiri = [panel][tab], kanan = [tab][panel]
    private fun fillRoot() {
        val r = root ?: return
        r.removeAllViews()
        val panelLp = LinearLayout.LayoutParams(d(250f), LinearLayout.LayoutParams.WRAP_CONTENT)
        val handleLp = LinearLayout.LayoutParams(d(26f), d(72f))
        val rad = d(8f).toFloat()
        if (dockSide == 0) {
            r.addView(panel, panelLp)
            r.addView(handleTv, handleLp)
            handleBg.cornerRadii = floatArrayOf(0f, 0f, rad, rad, rad, rad, 0f, 0f)
            params.gravity = Gravity.TOP or Gravity.START
        } else {
            r.addView(handleTv, handleLp)
            r.addView(panel, panelLp)
            handleBg.cornerRadii = floatArrayOf(rad, rad, 0f, 0f, 0f, 0f, rad, rad)
            params.gravity = Gravity.TOP or Gravity.END
        }
        params.x = 0
        params.y = clampY(barY)
    }

    private fun layoutDock() {
        val r = root ?: return
        fillRoot()
        try {
            wm.updateViewLayout(r, params)
        } catch (e: Exception) {
        }
    }

    private fun togglePanel() {
        val r = root ?: return
        panel.visibility = if (panel.visibility == View.GONE) View.VISIBLE else View.GONE
        handler.post {
            params.y = clampY(params.y)
            try {
                wm.updateViewLayout(r, params)
            } catch (e: Exception) {
            }
        }
    }

    private fun hidePanel() {
        if (panel.visibility != View.GONE) togglePanel()
    }

    // ---------- data ----------
    private fun memInfo(): ActivityManager.MemoryInfo {
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi
    }

    private fun availMb(): Long = memInfo().availMem / (1024L * 1024L)

    private fun netType(): String {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Seluler"
                else -> "Lainnya"
            }
        } catch (e: Exception) {
            "?"
        }
    }

    private fun currentBrightness(): Int =
        try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (e: Exception) {
            128
        }

    private fun applyBrightness(p: Int) {
        try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, p)
        } catch (e: Exception) {
        }
    }

    private fun restoreBrightness() {
        try {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, origMode)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, origBright)
        } catch (e: Exception) {
        }
    }

    private fun startPing() {
        running = true
        val t = Thread {
            while (running) {
                val host = hosts[Prefs.pingHost(this) % hosts.size]
                var res: Int
                try {
                    val t0 = System.nanoTime()
                    Socket().use { it.connect(InetSocketAddress(host, 443), 2000) }
                    res = ((System.nanoTime() - t0) / 1000000L).toInt()
                } catch (e: Exception) {
                    res = -2
                }
                lastPing = res
                try {
                    Thread.sleep(pingDelay)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        pingThread = t
        t.start()
    }

    private fun alertUser(msg: String) {
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun startTimer(min: Int) {
        timerEnd = System.currentTimeMillis() + min * 60000L
        updateStats()
    }

    private fun updateStats() {
        clockTv.text = timeFmt.format(Date())
        val sec = (System.currentTimeMillis() - startTime) / 1000
        sessionTv.text = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)

        // baterai + suhu
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
            batteryTv.text = String.format(Locale.US, "Baterai %d%%%s   %.1f C", pct, bolt, temp)
            val tcolor = if (temp >= 42.0) "#F87171" else if (temp >= 38.0) "#FACC15" else "#FFFFFF"
            batteryTv.setTextColor(Color.parseColor(tcolor))
            if (Prefs.tempAlert(this)) {
                if (temp >= 42.0 && !hotWarned) {
                    hotWarned = true
                    alertUser("HP panas (" + String.format(Locale.US, "%.1f", temp) + " C). Istirahatkan sebentar.")
                }
                if (temp < 39.0) hotWarned = false
                if (pct in 0..15 && !charging && !lowWarned) {
                    lowWarned = true
                    alertUser("Baterai tinggal $pct%. Siapkan charger.")
                }
                if (pct > 20) lowWarned = false
            }
        }

        // RAM
        val mi = memInfo()
        val freeMb = mi.availMem / (1024L * 1024L)
        val totMb = mi.totalMem / (1024L * 1024L)
        val usedPct = if (totMb > 0) 100L - freeMb * 100L / totMb else 0L
        ramTv.text = "RAM: $freeMb MB kosong ($usedPct% terpakai)"

        // ping + jaringan
        val p = lastPing
        val host = hosts[Prefs.pingHost(this) % hosts.size]
        if (p >= 0) {
            pingTv.text = "Ping $p ms  " + netType() + "  ($host)"
            val pc = if (p < 60) "#4ADE80" else if (p < 120) "#FACC15" else "#F87171"
            pingTv.setTextColor(Color.parseColor(pc))
        } else if (p == -2) {
            pingTv.text = "Ping: timeout  " + netType()
            pingTv.setTextColor(Color.parseColor("#F87171"))
        } else {
            pingTv.text = "Ping: mengukur..."
            pingTv.setTextColor(Color.parseColor("#FFFFFF"))
        }
        handleTv.text = if (Prefs.hud(this)) (if (p >= 0) "$p" else "--") else ""

        // timer
        if (timerEnd > 0L) {
            val left = (timerEnd - System.currentTimeMillis()) / 1000
            if (left <= 0L) {
                timerEnd = 0L
                timerTv.text = "Timer selesai"
                alertUser("Timer selesai!")
            } else {
                timerTv.text = String.format(Locale.US, "Timer %02d:%02d", left / 60, left % 60)
            }
        } else if (timerTv.text.toString() != "Timer selesai") {
            timerTv.text = "Timer: --"
        }

        // tombol rekam
        if (RecorderService.recording) {
            recBtn.text = "Stop"
            tint(recBtn, "#DC2626")
        } else {
            recBtn.text = "Rekam"
            tint(recBtn, "#902DE0")
        }

        // slider suara dan kecerahan
        if (panel.visibility == View.VISIBLE) {
            val canWrite = Settings.System.canWrite(this)
            brightBar.visibility = if (canWrite) View.VISIBLE else View.GONE
            brightBtn.visibility = if (canWrite) View.GONE else View.VISIBLE
            if (!volTracking) volBar.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (canWrite && !brightTracking) brightBar.progress = currentBrightness()
        }

        // pengingat istirahat
        if (breakMin > 0) {
            val mins = sec / 60
            val k = (mins / breakMin).toInt()
            if (k > breakDone) {
                breakDone = k
                alertUser("Sudah $mins menit main. Istirahat sebentar!")
            }
        }
    }

    // ---------- profil performa ----------
    private fun setSync(on: Boolean) {
        try {
            ContentResolver.setMasterSyncAutomatically(on)
        } catch (e: Exception) {
        }
    }

    private fun setKeepOn(on: Boolean) {
        val r = root ?: return
        params.flags = if (on) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try {
            wm.updateViewLayout(r, params)
        } catch (e: Exception) {
        }
    }

    private fun updateProfileButtons() {
        for (i in profBtns.indices) {
            tint(profBtns[i], if (i == profile) "#16A34A" else "#902DE0")
        }
    }

    private fun applyProfile(p: Int) {
        val canWrite = Settings.System.canWrite(this)
        if (profile == -1) {
            origBright = currentBrightness()
            origMode = try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            } catch (e: Exception) {
                0
            }
            origSync = ContentResolver.getMasterSyncAutomatically()
        }
        profile = p
        when (p) {
            0 -> {
                if (canWrite) applyBrightness(90)
                setSync(false)
                setDnd(true)
                setKeepOn(false)
                pingDelay = 10000L
            }
            1 -> {
                if (canWrite) restoreBrightness()
                setSync(origSync)
                setDnd(false)
                setKeepOn(false)
                pingDelay = 4000L
            }
            else -> {
                if (canWrite) applyBrightness(205)
                setSync(false)
                setDnd(true)
                setKeepOn(true)
                pingDelay = 2000L
                cleanRam()
            }
        }
        updateProfileButtons()
        val extra = if (canWrite) "" else " (izinkan atur kecerahan agar layar ikut berubah)"
        Toast.makeText(this, "Profil " + profNames[p] + extra, Toast.LENGTH_SHORT).show()
    }

    // ---------- tombol aksi ----------
    private fun setDnd(on: Boolean): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return false
        if (on && !dndOn) {
            prevFilter = nm.currentInterruptionFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            dndOn = true
        } else if (!on && dndOn) {
            val f = if (prevFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
                NotificationManager.INTERRUPTION_FILTER_ALL else prevFilter
            nm.setInterruptionFilter(f)
            dndOn = false
        }
        dndBtn.text = if (dndOn) "DND: ON" else "DND: OFF"
        return true
    }

    private fun toggleDnd() {
        if (!setDnd(!dndOn)) {
            Toast.makeText(this, "Beri izin Jangan Ganggu untuk WEAMONS dulu", Toast.LENGTH_LONG).show()
            val i = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
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

    private fun takeShot() {
        if (Build.VERSION.SDK_INT < 28 || !BoosterAccessibilityService.isEnabled(this)) {
            Toast.makeText(
                this,
                "Aktifkan layanan aksesibilitas WEAMONS dulu (lihat panduan di aplikasi)",
                Toast.LENGTH_LONG
            ).show()
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            return
        }
        hidePanel()
        handler.postDelayed({
            if (!BoosterAccessibilityService.screenshot()) {
                Toast.makeText(this, "Screenshot gagal, coba lagi", Toast.LENGTH_SHORT).show()
            }
        }, 600)
    }

    private fun toggleRecord() {
        if (RecorderService.recording) {
            val i = Intent(this, RecorderService::class.java)
            i.action = "STOP"
            startService(i)
        } else {
            hidePanel()
            val i = Intent(this, ProjectionActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    private fun openChat(pkg: String) {
        val li = packageManager.getLaunchIntentForPackage(pkg)
        if (li == null) {
            Toast.makeText(this, "Aplikasi tidak ditemukan", Toast.LENGTH_SHORT).show()
            return
        }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val w = screenW()
        val h = screenH()
        val bw = (w * 0.42f).toInt()
        val bh = (h * 0.8f).toInt()
        val left = (w - bw) / 2
        val top = (h - bh) / 2
        val opts = ActivityOptions.makeBasic()
        opts.setLaunchBounds(Rect(left, top, left + bw, top + bh))
        hidePanel()
        if (!popupHinted) {
            popupHinted = true
            Toast.makeText(
                this,
                "Jendela mengambang hanya jalan jika HP mendukung. Jika tidak, chat terbuka layar penuh.",
                Toast.LENGTH_LONG
            ).show()
        }
        try {
            startActivity(li, opts.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka aplikasi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun flushStats() {
        val pkg = gamePkg
        if (pkg != null && startTime > 0L) {
            Prefs.addPlay(this, pkg, (System.currentTimeMillis() - startTime) / 1000)
        }
    }

    override fun onDestroy() {
        running = false
        pingThread?.interrupt()
        handler.removeCallbacksAndMessages(null)
        if (root != null) {
            flushStats()
            if (dndOn) {
                try {
                    setDnd(false)
                } catch (e: Exception) {
                }
            }
            if (profile != -1) {
                setSync(origSync)
                if (Settings.System.canWrite(this)) restoreBrightness()
            }
            try {
                wm.removeView(root)
            } catch (e: Exception) {
            }
        }
        root = null
        super.onDestroy()
    }
}
