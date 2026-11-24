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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Objects
import kotlin.math.abs
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        @Volatile
        var alive = false
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: LinearLayout? = null
    private lateinit var panel: LinearLayout
    private lateinit var panelScroll: ScrollView
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
    private val profIcons = arrayOf("\uD83D\uDD0B", "\u2696\uFE0F", "\u26A1")

    private var startTime = 0L
    private var gamePkg: String? = null
    private var dndOn = false
    private var prevFilter = NotificationManager.INTERRUPTION_FILTER_ALL
    private var sc = 0.8f
    private var dockSide = 0
    private var barFrac = 0.5f
    private var lastW = 0
    private var lastH = 0
    private var crossView: CrosshairView? = null
    private var crossParams: WindowManager.LayoutParams? = null
    private var crossSig = 0
    private lateinit var crossBtn: Button
    private lateinit var monTv: TextView
    private lateinit var graph: GraphView
    private lateinit var meterBtn: Button
    private var meter: MeterOverlay? = null
    private lateinit var macroPlayBtn: Button
    private lateinit var macroRecBtn: Button
    private lateinit var macroTv: TextView
    private var macroPlaying = false
    private var macroGen = 0
    private var recView: FrameLayout? = null
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
            checkRotation()
            syncCross()
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
        val boost = intent?.getBooleanExtra("boost", false) ?: false
        val auto = intent?.getBooleanExtra("auto", false) ?: false
        if (root != null) {
            if (newPkg != null && newPkg != gamePkg) {
                flushStats()
                startTime = System.currentTimeMillis()
                breakDone = 0
                gamePkg = newPkg
            }
            if (boost) applyProfile(2)
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
        barFrac = Prefs.barFrac(this)
        breakMin = Prefs.breakMin(this)
        startTime = System.currentTimeMillis()
        buildOverlay()
        syncCross()
        if (Prefs.autoDnd(this)) setDnd(true)
        val sp = Prefs.startProfile(this)
        if (boost) applyProfile(2)
        else if (sp in 0..2) applyProfile(sp)
        else if (auto) applyProfile(2)
        alive = true
        if (Prefs.meterOn(this)) {
            meter = MeterOverlay(this, wm)
            meter?.show()
            updateMeterBtn()
        }
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

    private fun btn(label: String, color: String = "#6D28D9", onClick: () -> Unit): Button {
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
        b.typeface = android.graphics.Typeface.DEFAULT_BOLD
        tint(b, color)
        b.setOnClickListener { onClick() }
        return b
    }

    // kartu bagian dengan judul kecil
    private fun section(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(d(8f), d(6f), d(8f), d(8f))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#33FFFFFF"))
        bg.cornerRadius = d(10f).toFloat()
        c.background = bg
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, d(8f), 0, 0)
        panel.addView(c, lp)
        val t = txt(10f, "#B79CFF")
        t.text = title
        t.setTypeface(null, android.graphics.Typeface.BOLD)
        c.addView(t)
        return c
    }

    private fun rowLp(weight: Float): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, d(36f), weight)
        lp.setMargins(d(3f), d(5f), d(3f), 0)
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

    private fun rootH(): Int {
        val r = root
        return if (r != null && r.height > 0) r.height else d(72f)
    }

    // posisi Y dari pecahan tinggi layar; aman untuk portrait maupun landscape
    private fun targetY(): Int =
        (barFrac.coerceIn(0f, 1f) * max(0, screenH() - rootH())).toInt()

    private fun dpx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun checkRotation() {
        val w = screenW()
        val h = screenH()
        if (lastW != 0 && (w != lastW || h != lastH)) {
            layoutDock()
        }
        lastW = w
        lastH = h
    }

    // ---------- crosshair ----------
    private fun addCross() {
        if (crossView != null) return
        val v = CrosshairView(this)
        val box = dpx(CrosshairView.BOX_DP)
        val p = WindowManager.LayoutParams(
            box,
            box,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.CENTER
        crossView = v
        crossParams = p
        applyCrossPrefs()
        try {
            wm.addView(v, p)
        } catch (e: Exception) {
            crossView = null
            crossParams = null
        }
    }

    private fun applyCrossPrefs() {
        val v = crossView ?: return
        val p = crossParams ?: return
        v.style = Prefs.crossInt(this, "style", 2)
        v.colorIndex = Prefs.crossInt(this, "color", 0)
        v.sizeDp = Prefs.crossInt(this, "size", 12)
        v.thickDp = Prefs.crossInt(this, "thick", 2)
        v.gapDp = Prefs.crossInt(this, "gap", 4)
        v.opacity = Prefs.crossInt(this, "opacity", 90)
        p.x = dpx(Prefs.crossInt(this, "offx", 0))
        p.y = dpx(Prefs.crossInt(this, "offy", 0))
    }

    private fun updateCross() {
        val v = crossView ?: return
        val p = crossParams ?: return
        applyCrossPrefs()
        v.invalidate()
        try {
            wm.updateViewLayout(v, p)
        } catch (e: Exception) {
        }
    }

    private fun removeCross() {
        val v = crossView ?: return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
        }
        crossView = null
        crossParams = null
    }

    private fun syncCross() {
        val on = Prefs.crossOn(this)
        if (on && crossView == null) addCross()
        if (!on && crossView != null) removeCross()
        if (::crossBtn.isInitialized) crossBtn.text = if (on) "Crosshair: ON" else "Crosshair: OFF"
        if (on && crossView != null) {
            val sig = Objects.hash(
                Prefs.crossInt(this, "style", 2),
                Prefs.crossInt(this, "color", 0),
                Prefs.crossInt(this, "size", 12),
                Prefs.crossInt(this, "thick", 2),
                Prefs.crossInt(this, "gap", 4),
                Prefs.crossInt(this, "opacity", 90),
                Prefs.crossInt(this, "offx", 0),
                Prefs.crossInt(this, "offy", 0)
            )
            if (sig != crossSig) {
                crossSig = sig
                updateCross()
            }
        }
    }

    private fun toggleCross() {
        Prefs.setCrossOn(this, !Prefs.crossOn(this))
        syncCross()
    }

    private fun cycleCross(key: String, count: Int, def: Int) {
        Prefs.setCrossInt(this, key, (Prefs.crossInt(this, key, def) + 1) % count)
        syncCross()
    }

    // ---------- membangun bilah ----------
    private fun buildOverlay() {
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(d(12f), d(10f), d(12f), d(12f))
        val panelBg = GradientDrawable()
        panelBg.setColor(Color.parseColor("#FA14101F"))
        panelBg.cornerRadius = d(14f).toFloat()
        panel.background = panelBg
        panelScroll = ScrollView(this)
        panelScroll.isVerticalScrollBarEnabled = false
        panelScroll.overScrollMode = View.OVER_SCROLL_NEVER
        panelScroll.addView(panel, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        panelScroll.visibility = View.GONE

        clockTv = txt(18f, "#C9A2FF")
        sessionTv = txt(12f, "#FFFFFF")
        sessionTv.setPadding(d(10f), 0, 0, 0)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(clockTv)
        head.addView(sessionTv)
        panel.addView(head)

        // ringkasan + grafik CPU/RAM seperti GamePanel
        monTv = txt(11f, "#C9D6FF")
        monTv.gravity = Gravity.CENTER
        monTv.setPadding(0, d(6f), 0, d(4f))
        panel.addView(monTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        graph = GraphView(this)
        panel.addView(graph, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, d(64f)
        ))

        batteryTv = txt(12f, "#FFFFFF")
        ramTv = txt(12f, "#FFFFFF")
        pingTv = txt(12f, "#FFFFFF")
        pingTv.setOnClickListener {
            Prefs.setPingHost(this, (Prefs.pingHost(this) + 1) % hosts.size)
            lastPing = -1
        }
        val stat = section("STATUS")
        stat.addView(batteryTv)
        stat.addView(ramTv)
        stat.addView(pingTv)

        // timer cepat
        timerTv = txt(12f, "#FFFFFF")
        timerTv.setOnClickListener { timerEnd = 0L }
        val timerRow = LinearLayout(this)
        timerRow.orientation = LinearLayout.HORIZONTAL
        timerRow.gravity = Gravity.CENTER_VERTICAL
        timerRow.addView(timerTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        timerRow.addView(btn("5m", "#4338CA") { startTimer(5) }, rowLp(1f))
        timerRow.addView(btn("10m", "#4338CA") { startTimer(10) }, rowLp(1f))
        timerRow.addView(btn("15m", "#4338CA") { startTimer(15) }, rowLp(1f))
        section("TIMER").addView(timerRow)

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
        val sl = section("SUARA DAN LAYAR")
        sl.addView(volRow)

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
        sl.addView(brightRow)

        // profil performa
        val profSec = section("PROFIL PERFORMA")
        val profRow = LinearLayout(this)
        profRow.orientation = LinearLayout.HORIZONTAL
        for (i in 0..2) {
            val b = btn(profIcons[i] + " " + profNames[i], "#3B3363") { applyProfile(i) }
            profBtns.add(b)
            profRow.addView(b, rowLp(1f))
        }
        profSec.addView(profRow)

        // aksi cepat 1: screenshot, rekam, bersihkan RAM
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(btn("\uD83D\uDCF7 Foto", "#2563EB") { takeShot() }, rowLp(1f))
        recBtn = btn("\u23FA Rekam", "#E11D48") { toggleRecord() }
        row1.addView(recBtn, rowLp(1.1f))
        row1.addView(btn("\uD83E\uDDF9 RAM", "#0D9488") { cleanRam() }, rowLp(1f))
        val act = section("AKSI CEPAT")
        act.addView(row1)

        // aksi cepat 2: DND, chat pop-up, tutup
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        dndBtn = btn("DND: OFF", "#D97706") { toggleDnd() }
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
                row2.addView(btn("\uD83D\uDCAC " + c.second, "#16A34A") { openChat(c.first) }, rowLp(1f))
                count++
            }
        }
        row2.addView(btn("\u2716 Tutup", "#475569") { stopSelf() }, rowLp(0.9f))
        act.addView(row2)

        // aksi cepat 3: crosshair
        val row3 = LinearLayout(this)
        row3.orientation = LinearLayout.HORIZONTAL
        crossBtn = btn("Crosshair: OFF", "#0891B2") { toggleCross() }
        row3.addView(crossBtn, rowLp(1.6f))
        row3.addView(btn("Gaya", "#6D28D9") { cycleCross("style", CrosshairView.STYLE_NAMES.size, 2) }, rowLp(1f))
        row3.addView(btn("Warna", "#6D28D9") { cycleCross("color", CrosshairView.COLORS.size, 0) }, rowLp(1f))
        section("CROSSHAIR").addView(row3)

        // meter melayang: CPU / RAM / BAT / TEMP / HZ
        meterBtn = btn("Meter: OFF", "#0F766E") { toggleMeter() }
        section("METER").addView(meterBtn, rowLp(1f))
        // makro
        val mac = section("MAKRO (ULANG KETUKAN)")
        macroTv = txt(11f, "#FFFFFF")
        mac.addView(macroTv)
        val row4 = LinearLayout(this)
        row4.orientation = LinearLayout.HORIZONTAL
        macroRecBtn = btn("\u23FA Rekam", "#E11D48") { startRecording() }
        macroPlayBtn = btn("\u25B6 Putar", "#16A34A") { toggleMacro() }
        row4.addView(macroRecBtn, rowLp(1f))
        row4.addView(macroPlayBtn, rowLp(1f))
        mac.addView(row4)
        val row5 = LinearLayout(this)
        row5.orientation = LinearLayout.HORIZONTAL
        row5.addView(btn("\uD83D\uDC46 Auto-tap 1 tombol", "#0891B2") { startRecording(true) }, rowLp(1f))
        mac.addView(row5)

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
        params.y = targetY()

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
                            barFrac = params.y.toFloat() / max(1, screenH() - r.height)
                            Prefs.setSide(this@OverlayService, dockSide)
                            Prefs.setBarFrac(this@OverlayService, barFrac)
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
        val panelLp = LinearLayout.LayoutParams(d(280f), LinearLayout.LayoutParams.WRAP_CONTENT)
        val handleLp = LinearLayout.LayoutParams(d(26f), d(72f))
        val rad = d(8f).toFloat()
        if (dockSide == 0) {
            r.addView(panelScroll, panelLp)
            r.addView(handleTv, handleLp)
            handleBg.cornerRadii = floatArrayOf(0f, 0f, rad, rad, rad, rad, 0f, 0f)
            params.gravity = Gravity.TOP or Gravity.START
        } else {
            r.addView(handleTv, handleLp)
            r.addView(panelScroll, panelLp)
            handleBg.cornerRadii = floatArrayOf(rad, rad, 0f, 0f, 0f, 0f, rad, rad)
            params.gravity = Gravity.TOP or Gravity.END
        }
        params.x = 0
        params.y = targetY()
        fitPanel()
    }

    // tinggi panel dibatasi setinggi layar; sisanya bisa digulir
    private fun fitPanel() {
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(d(280f), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = minOf(panel.measuredHeight, screenH() - dpx(24))
        val lp = panelScroll.layoutParams
        if (lp != null && lp.height != h) {
            lp.height = h
            panelScroll.layoutParams = lp
        }
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
        if (panelScroll.visibility == View.GONE) fitPanel()
        panelScroll.visibility = if (panelScroll.visibility == View.GONE) View.VISIBLE else View.GONE
        handler.post {
            params.y = clampY(params.y)
            try {
                wm.updateViewLayout(r, params)
            } catch (e: Exception) {
            }
        }
    }

    private fun hidePanel() {
        if (panelScroll.visibility != View.GONE) togglePanel()
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

        // ringkasan, grafik, dan meter
        val snap = SysMon.read(this)
        val cpuTxt = if (snap.cpu >= 0) "${snap.cpu}%" else "--"
        monTv.text = "Cpu $cpuTxt   Ram ${snap.ram}%   Baterai ${snap.bat}%"
        graph.push(snap.cpu, snap.ram)
        meter?.update(snap)

        // tombol rekam
        if (RecorderService.recording) {
            recBtn.text = "\u23F9 Stop"
            tint(recBtn, "#991B1B")
        } else {
            recBtn.text = "\u23FA Rekam"
            tint(recBtn, "#E11D48")
        }

        // makro
        macroPlayBtn.text = if (macroPlaying) "\u23F9 Stop" else "\u25B6 Putar"
        tint(macroPlayBtn, if (macroPlaying) "#991B1B" else "#16A34A")
        val mc = Macro.count(this)
        macroTv.text = if (macroPlaying) "Makro berjalan ($mc ketukan, diulang terus)"
        else if (mc == 0) "Belum ada makro. Tekan Rekam."
        else "Makro tersimpan: $mc ketukan"

        // slider suara dan kecerahan
        if (panelScroll.visibility == View.VISIBLE) {
            fitPanel()
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
            tint(profBtns[i], if (i == profile) "#16A34A" else "#3B3363")
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

    private fun updateMeterBtn() {
        val on = meter?.isShown == true
        meterBtn.text = if (on) "Meter: ON" else "Meter: OFF"
    }

    private fun toggleMeter() {
        val m = meter ?: MeterOverlay(this, wm).also { meter = it }
        if (m.isShown) {
            m.hide()
            Prefs.setMeterOn(this, false)
        } else {
            m.show()
            Prefs.setMeterOn(this, true)
        }
        updateMeterBtn()
    }

    // ---------- makro ----------
    private fun needAccessibility(): Boolean {
        if (Build.VERSION.SDK_INT < 24 || !BoosterAccessibilityService.isEnabled(this)) {
            Toast.makeText(
                this,
                "Aktifkan layanan aksesibilitas WEAMONS dulu (lihat panduan di aplikasi)",
                Toast.LENGTH_LONG
            ).show()
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            return true
        }
        return false
    }

    private fun stopMacro() {
        macroPlaying = false
        macroGen++
    }

    private fun toggleMacro() {
        if (macroPlaying) {
            stopMacro()
            return
        }
        val steps = Macro.load(this)
        if (steps.isEmpty()) {
            Toast.makeText(this, "Belum ada makro. Tekan Rekam dulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (needAccessibility()) return
        macroPlaying = true
        macroGen++
        hidePanel()
        Toast.makeText(this, "Makro jalan. Buka panel lalu tekan Stop untuk berhenti.", Toast.LENGTH_LONG).show()
        val g = macroGen
        handler.postDelayed({ macroStep(steps, 0, g) }, 800)
    }

    private fun macroStep(steps: List<Macro.Step>, i: Int, gen: Int) {
        if (!macroPlaying || gen != macroGen) return
        val s = steps[i]
        BoosterAccessibilityService.tap(s.x.toFloat(), s.y.toFloat(), s.dur)
        val next = i + 1
        val wait = if (next < steps.size) {
            s.dur + steps[next].delay
        } else {
            s.dur + Prefs.macroLoopMs(this).toLong()
        }
        val ni = if (next < steps.size) next else 0
        handler.postDelayed({ macroStep(steps, ni, gen) }, max(wait, 30L))
    }

    private fun startRecording(single: Boolean = false) {
        if (recView != null) return
        if (needAccessibility()) return
        stopMacro()
        hidePanel()
        val steps = ArrayList<Macro.Step>()
        val fl = FrameLayout(this)
        fl.setBackgroundColor(Color.parseColor("#33000000"))
        val info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.textSize = 13f
        info.setPadding(dpx(12), dpx(8), dpx(12), dpx(8))
        info.text = if (single) "Ketuk SATU tombol yang mau ditekan otomatis" else "MEREKAM: ketuk titik yang ingin diulang (0 ketukan)"
        val infoBg = GradientDrawable()
        infoBg.setColor(Color.parseColor("#E6E11D48"))
        infoBg.cornerRadius = dpx(10).toFloat()
        info.background = infoBg
        val ilp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        ilp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ilp.topMargin = dpx(12)
        fl.addView(info, ilp)

        val done = Button(this)
        done.text = "Selesai"
        done.isAllCaps = false
        done.setTextColor(Color.WHITE)
        val dbg = GradientDrawable()
        dbg.setColor(Color.parseColor("#16A34A"))
        dbg.cornerRadius = dpx(10).toFloat()
        done.background = dbg
        val dlp = FrameLayout.LayoutParams(dpx(140), dpx(44))
        dlp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        dlp.bottomMargin = dpx(24)
        if (!single) fl.addView(done, dlp)

        var downT = 0L
        var dx = 0
        var dy = 0
        var lastUp = 0L
        fl.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downT = System.currentTimeMillis()
                    dx = e.rawX.toInt()
                    dy = e.rawY.toInt()
                }
                MotionEvent.ACTION_UP -> {
                    val now = System.currentTimeMillis()
                    val dur = (now - downT).coerceIn(30L, 5000L)
                    val delay = if (steps.isEmpty()) 0L else (downT - lastUp).coerceIn(0L, 600000L)
                    if (single) {
                        steps.add(Macro.Step(dx, dy, 60L, 0L))
                        done.performClick()
                    } else {
                        steps.add(Macro.Step(dx, dy, dur, delay))
                        lastUp = now
                        info.text = "MEREKAM: ketuk titik yang ingin diulang (${steps.size} ketukan)"
                    }
                }
            }
            true
        }
        done.setOnClickListener {
            val rv = recView
            if (rv != null) {
                try {
                    wm.removeView(rv)
                } catch (e: Exception) {
                }
            }
            recView = null
            if (steps.isNotEmpty()) {
                Macro.save(this, steps)
                Toast.makeText(this, "Makro tersimpan: ${steps.size} ketukan", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Tidak ada ketukan direkam, makro lama dipertahankan", Toast.LENGTH_SHORT).show()
            }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        recView = fl
        try {
            wm.addView(fl, p)
        } catch (e: Exception) {
            recView = null
        }
    }

    private fun flushStats() {
        val pkg = gamePkg
        if (pkg != null && startTime > 0L) {
            Prefs.addPlay(this, pkg, (System.currentTimeMillis() - startTime) / 1000)
        }
    }

    override fun onDestroy() {
        alive = false
        running = false
        pingThread?.interrupt()
        handler.removeCallbacksAndMessages(null)
        if (root != null) {
            meter?.hide()
            removeCross()
            stopMacro()
            recView?.let {
                try {
                    wm.removeView(it)
                } catch (e: Exception) {
                }
            }
            recView = null
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
