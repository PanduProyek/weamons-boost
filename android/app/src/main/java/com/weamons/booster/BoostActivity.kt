package com.weamons.booster

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// Layar Booster (landscape): kartu game, tombol BOOST, status, ping/jitter, dan alat cepat.
class BoostActivity : Activity() {

    private class Tool(val box: LinearLayout, val icon: TextView, val label: TextView)

    private val handler = Handler(Looper.getMainLooper())
    private val hosts = arrayOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    private lateinit var topStats: TextView
    private lateinit var ipTv: TextView
    private lateinit var pingTv: TextView
    private lateinit var jitterTv: TextView
    private lateinit var gameIcon: ImageView
    private lateinit var gameName: TextView
    private lateinit var gameVer: TextView
    private lateinit var brightSlider: VSlider
    private lateinit var volSlider: VSlider
    private lateinit var optTool: Tool
    private lateinit var netTool: Tool
    private lateinit var speedTool: Tool
    private lateinit var autoTool: Tool
    private lateinit var lockTool: Tool
    private lateinit var dndTool: Tool
    private lateinit var audioTool: Tool
    private lateinit var audio: AudioManager

    @Volatile
    private var running = false

    @Volatile
    private var ping = -1

    @Volatile
    private var jitter = -1

    @Volatile
    private var publicIp = "0.0.0.0"

    private val samples = ArrayList<Int>()
    private var pingThread: Thread? = null
    private var speedRunning = false
    private var optRunning = false
    private var prevVol = -1

    private val loop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(AudioManager::class.java)
        val frame = FrameLayout(this)
        frame.addView(HudFrameView(this), match())
        frame.addView(buildContent(), match())

        val back = TextView(this)
        back.text = "←"
        back.setTextColor(Color.WHITE)
        back.textSize = 22f
        back.gravity = Gravity.CENTER
        back.setOnClickListener { finish() }
        val blp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START)
        blp.setMargins(dp(12), dp(8), 0, 0)
        frame.addView(back, blp)

        val gear = TextView(this)
        gear.text = "⚙"
        gear.setTextColor(Color.parseColor("#9AA4B2"))
        gear.textSize = 20f
        gear.gravity = Gravity.CENTER
        gear.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        // ikon pengaturan di sebelah tombol kembali, agar tidak menutupi slider
        val glp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START)
        glp.setMargins(dp(60), dp(8), 0, 0)
        frame.addView(gear, glp)

        setContentView(frame)

        var pkg = Prefs.boostPkg(this)
        if (pkg == null) {
            pkg = Prefs.favs(this).firstOrNull()
            if (pkg != null) Prefs.setBoostPkg(this, pkg)
        }
        renderGame()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onResume() {
        super.onResume()
        renderGame()
        startPing()
        fetchIp()
        handler.post(loop)
    }

    override fun onPause() {
        running = false
        pingThread?.interrupt()
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // ---------- susunan tampilan ----------
    private fun buildContent(): View {
        val content = LinearLayout(this)
        content.orientation = LinearLayout.HORIZONTAL
        content.setPadding(dp(14), dp(10), dp(14), dp(10))

        // kiri: alat jaringan + info
        val left = LinearLayout(this)
        left.orientation = LinearLayout.HORIZONTAL
        left.gravity = Gravity.CENTER_VERTICAL
        val leftTools = LinearLayout(this)
        leftTools.orientation = LinearLayout.VERTICAL
        leftTools.gravity = Gravity.CENTER
        optTool = tool("⚡", "Optimizer") { runOptimizer() }
        netTool = tool("🚫", "Net Blocker") { toggleNet() }
        speedTool = tool("📶", "SpeedTest") { speedTest() }
        leftTools.addView(optTool.box)
        leftTools.addView(netTool.box)
        leftTools.addView(speedTool.box)
        left.addView(leftTools, LinearLayout.LayoutParams(dp(66), LinearLayout.LayoutParams.WRAP_CONTENT))

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.gravity = Gravity.CENTER_VERTICAL
        info.setPadding(dp(8), 0, 0, 0)
        ipTv = infoValue()
        pingTv = infoValue()
        jitterTv = infoValue()
        ipTv.setOnClickListener { fetchIp() }
        info.addView(infoLabel("IP"))
        info.addView(ipTv)
        info.addView(infoLabel("Ping"))
        info.addView(pingTv)
        info.addView(infoLabel("Jitter"))
        info.addView(jitterTv)
        left.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        content.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.4f))

        // tengah: status atas, kartu game, tombol BOOST
        val center = LinearLayout(this)
        center.orientation = LinearLayout.VERTICAL
        center.gravity = Gravity.CENTER_HORIZONTAL
        topStats = TextView(this)
        topStats.setTextColor(Color.WHITE)
        topStats.textSize = 11f
        topStats.typeface = Typeface.DEFAULT_BOLD
        topStats.gravity = Gravity.CENTER
        topStats.text = "CPU --%   RAM --%   BAT --%"
        center.addView(topStats, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val card = FrameLayout(this)
        val cardBg = GradientDrawable()
        cardBg.setColor(Color.parseColor("#E6101012"))
        cardBg.setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        cardBg.cornerRadius = dp(8).toFloat()
        card.background = cardBg
        card.setOnClickListener { if (Prefs.boostPkg(this) == null) pickGame() }

        val menu = TextView(this)
        menu.text = "⋮"
        menu.setTextColor(Color.WHITE)
        menu.textSize = 20f
        menu.gravity = Gravity.CENTER
        menu.setOnClickListener { pickGame() }
        card.addView(menu, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START))

        gameVer = TextView(this)
        gameVer.setTextColor(Color.WHITE)
        gameVer.textSize = 9f
        val vlp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        vlp.setMargins(0, dp(10), dp(10), 0)
        card.addView(gameVer, vlp)

        val gcol = LinearLayout(this)
        gcol.orientation = LinearLayout.VERTICAL
        gcol.gravity = Gravity.CENTER
        gameIcon = ImageView(this)
        gameIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        gcol.addView(gameIcon, LinearLayout.LayoutParams(dp(56), dp(56)))
        gameName = TextView(this)
        gameName.setTextColor(Color.WHITE)
        gameName.textSize = 14f
        gameName.typeface = Typeface.DEFAULT_BOLD
        gameName.gravity = Gravity.CENTER
        gameName.maxLines = 1
        gameName.ellipsize = TextUtils.TruncateAt.END
        gameName.setPadding(dp(8), dp(6), dp(8), 0)
        gcol.addView(gameName)
        card.addView(gcol, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        clp.setMargins(0, dp(6), 0, dp(6))
        center.addView(card, clp)

        val boostBtn = BoostButtonView(this)
        boostBtn.setOnClickListener { boost() }
        center.addView(boostBtn, LinearLayout.LayoutParams(dp(190), dp(44)))
        content.addView(center, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3.4f))

        // kanan: audio, alat layar, slider
        val right = LinearLayout(this)
        right.orientation = LinearLayout.HORIZONTAL
        right.gravity = Gravity.CENTER_VERTICAL
        audioTool = tool("🔊", "Audio Maks") { toggleAudioMax() }
        right.addView(audioTool.box, LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.WRAP_CONTENT))

        val rightTools = LinearLayout(this)
        rightTools.orientation = LinearLayout.VERTICAL
        rightTools.gravity = Gravity.CENTER
        autoTool = tool("A", "Terang Auto") { setBrightMode(true) }
        lockTool = tool("🔒", "Terang Kunci") { setBrightMode(false) }
        dndTool = tool("⛔", "Jangan Ganggu") { toggleDnd() }
        rightTools.addView(autoTool.box)
        rightTools.addView(lockTool.box)
        rightTools.addView(dndTool.box)
        right.addView(rightTools, LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.WRAP_CONTENT))

        val sliders = LinearLayout(this)
        sliders.orientation = LinearLayout.HORIZONTAL
        sliders.gravity = Gravity.CENTER
        brightSlider = VSlider(this, "☀")
        brightSlider.max = 255
        brightSlider.onChange = { p -> setBrightness(p) }
        brightSlider.onLocked = { askWriteSettings() }
        volSlider = VSlider(this, "🔊")
        volSlider.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volSlider.onChange = { p ->
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            } catch (e: Exception) {
            }
        }
        val slp = LinearLayout.LayoutParams(dp(30), dp(150))
        slp.setMargins(dp(4), 0, dp(4), 0)
        sliders.addView(brightSlider, slp)
        val slp2 = LinearLayout.LayoutParams(dp(30), dp(150))
        slp2.setMargins(dp(4), 0, dp(4), 0)
        sliders.addView(volSlider, slp2)
        right.addView(sliders, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        content.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3.4f))
        return content
    }

    private fun tool(glyph: String, name: String, onClick: () -> Unit): Tool {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(0, dp(3), 0, dp(3))
        val icon = TextView(this)
        icon.text = glyph
        icon.setTextColor(Color.WHITE)
        icon.textSize = 18f
        icon.gravity = Gravity.CENTER
        icon.background = toolBg(false)
        box.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
        val label = TextView(this)
        label.text = name
        label.setTextColor(Color.WHITE)
        label.textSize = 9f
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.setPadding(0, dp(2), 0, 0)
        box.addView(label, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        box.setOnClickListener { onClick() }
        return Tool(box, icon, label)
    }

    private fun toolBg(active: Boolean): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.parseColor(if (active) "#0A6FC4" else "#3A3A3E"))
        g.cornerRadius = dp(9).toFloat()
        return g
    }

    private fun setActive(t: Tool, on: Boolean) {
        t.icon.background = toolBg(on)
    }

    private fun infoLabel(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextColor(Color.WHITE)
        t.textSize = 10f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(0, dp(6), 0, 0)
        return t
    }

    private fun infoValue(): TextView {
        val t = TextView(this)
        t.setTextColor(Color.parseColor("#22E58B"))
        t.textSize = 11f
        t.typeface = Typeface.DEFAULT_BOLD
        t.text = "--"
        return t
    }

    // ---------- game terpilih ----------
    @Suppress("DEPRECATION")
    private fun renderGame() {
        val pkg = Prefs.boostPkg(this)
        if (pkg == null) {
            gameIcon.setImageResource(R.drawable.ic_launcher)
            gameName.text = "Pilih game"
            gameVer.text = ""
            return
        }
        try {
            val pm = packageManager
            gameIcon.setImageDrawable(pm.getApplicationIcon(pkg))
            gameName.text = pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            val pi = pm.getPackageInfo(pkg, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            gameVer.text = "$code (${pi.versionName ?: "-"})"
        } catch (e: Exception) {
            gameName.text = pkg
            gameVer.text = ""
        }
    }

    private fun pickGame() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val favs = Prefs.favs(this)
        val list: List<Pair<ResolveInfo, String>> = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { Pair(it, it.loadLabel(pm).toString()) }
            .sortedWith(
                compareBy<Pair<ResolveInfo, String>>(
                    { !favs.contains(it.first.activityInfo.packageName) },
                    { it.second.lowercase() }
                )
            )
        val names = Array(list.size) {
            (if (favs.contains(list[it].first.activityInfo.packageName)) "★ " else "") + list[it].second
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pilih game")
            .setItems(names) { _, which ->
                Prefs.setBoostPkg(this, list[which].first.activityInfo.packageName)
                renderGame()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---------- BOOST ----------
    private fun boost() {
        val pkg = Prefs.boostPkg(this)
        if (pkg == null) {
            pickGame()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan dulu tampil di atas aplikasi lain", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Toast.makeText(this, "Game tidak bisa dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = Intent(this, OverlayService::class.java)
        svc.putExtra("pkg", pkg)
        svc.putExtra("boost", true)
        startForegroundService(svc)
        startActivity(launch)
    }

    // ---------- alat kiri ----------
    private fun runOptimizer() {
        if (optRunning) return
        optRunning = true
        optTool.label.text = "Membersihkan"
        Optimizer.run(this, Prefs.boostPkg(this)) { before, after ->
            optRunning = false
            optTool.label.text = "Optimizer"
            Toast.makeText(this, "RAM kosong: $before MB menjadi $after MB", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleNet() {
        if (NetBlockService.active) {
            val i = Intent(this, NetBlockService::class.java)
            i.action = "STOP"
            startService(i)
            Toast.makeText(this, "Net Blocker dimatikan", Toast.LENGTH_SHORT).show()
            return
        }
        if (Prefs.boostPkg(this) == null) {
            Toast.makeText(this, "Pilih game dulu, agar game tetap online", Toast.LENGTH_LONG).show()
            pickGame()
            return
        }
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 77) else startNet()
    }

    private fun startNet() {
        startForegroundService(Intent(this, NetBlockService::class.java))
        Toast.makeText(
            this,
            "Net Blocker aktif: internet aplikasi lain diblokir, game tetap online",
            Toast.LENGTH_LONG
        ).show()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) startNet()
    }

    private fun speedTest() {
        if (speedRunning) return
        speedRunning = true
        speedTool.label.text = "Menguji..."
        Thread {
            var mbps = -1.0
            try {
                val c = URL("https://speed.cloudflare.com/__down?bytes=20000000")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                val t0 = System.nanoTime()
                var total = 0L
                c.inputStream.use { ins ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (System.nanoTime() - t0 > 7_000_000_000L) break
                    }
                }
                val sec = (System.nanoTime() - t0) / 1e9
                if (sec > 0 && total > 0) mbps = total * 8.0 / sec / 1e6
                c.disconnect()
            } catch (e: Exception) {
            }
            runOnUiThread {
                speedRunning = false
                speedTool.label.text =
                    if (mbps >= 0) String.format(Locale.US, "%.1f Mbps", mbps) else "Gagal"
                handler.postDelayed({ speedTool.label.text = "SpeedTest" }, 10000)
            }
        }.start()
    }

    // ---------- alat kanan ----------
    private fun canWrite(): Boolean = Settings.System.canWrite(this)

    private fun askWriteSettings() {
        Toast.makeText(this, "Izinkan atur kecerahan layar dulu", Toast.LENGTH_LONG).show()
        startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    private fun currentBrightness(): Int =
        try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (e: Exception) {
            128
        }

    private fun isAutoBright(): Boolean =
        try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (e: Exception) {
            false
        }

    private fun setBrightness(p: Int) {
        if (!canWrite()) return
        try {
            Settings.System.putInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, max(p, 10))
        } catch (e: Exception) {
        }
    }

    private fun setBrightMode(auto: Boolean) {
        if (!canWrite()) {
            askWriteSettings()
            return
        }
        try {
            Settings.System.putInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
        } catch (e: Exception) {
        }
        refresh()
    }

    private fun dndOn(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        val f = nm.currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL &&
            f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun toggleDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            Toast.makeText(this, "Beri izin Jangan Ganggu untuk WEAMONS dulu", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        nm.setInterruptionFilter(
            if (dndOn()) NotificationManager.INTERRUPTION_FILTER_ALL
            else NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )
        refresh()
    }

    private fun toggleAudioMax() {
        val mx = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            if (cur < mx) {
                prevVol = cur
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, mx, 0)
            } else if (prevVol >= 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, prevVol, 0)
                prevVol = -1
            }
        } catch (e: Exception) {
        }
        refresh()
    }

    // ---------- pembaruan berkala ----------
    private fun refresh() {
        val s = SysMon.read(this)
        val cpuTxt = if (s.cpu >= 0) "${s.cpu}%" else "--"
        topStats.text = "CPU $cpuTxt   RAM ${s.ram}%   BAT ${s.bat}%"

        ipTv.text = publicIp
        val p = ping
        if (p >= 0) {
            pingTv.text = "${p}ms"
            pingTv.setTextColor(
                Color.parseColor(if (p < 60) "#22E58B" else if (p < 120) "#FACC15" else "#F87171")
            )
        } else {
            pingTv.text = if (p == -2) "timeout" else "--"
            pingTv.setTextColor(Color.parseColor(if (p == -2) "#F87171" else "#22E58B"))
        }
        jitterTv.text = if (jitter >= 0) "${jitter}ms" else "--"

        val w = canWrite()
        brightSlider.locked = !w
        if (!brightSlider.tracking && w) brightSlider.value = currentBrightness()
        if (!volSlider.tracking) {
            volSlider.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        val auto = isAutoBright()
        setActive(autoTool, w && auto)
        setActive(lockTool, w && !auto)
        setActive(dndTool, dndOn())
        setActive(netTool, NetBlockService.active)
        setActive(audioTool, volSlider.value >= volSlider.max && volSlider.max > 0)
    }

    private fun startPing() {
        running = true
        pingThread?.interrupt()
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
                ping = res
                if (res >= 0) {
                    samples.add(res)
                    while (samples.size > 10) samples.removeAt(0)
                    if (samples.size >= 2) {
                        var sum = 0
                        for (i in 1 until samples.size) sum += abs(samples[i] - samples[i - 1])
                        jitter = sum / (samples.size - 1)
                    }
                }
                try {
                    Thread.sleep(1500)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        pingThread = t
        t.start()
    }

    private fun fetchIp() {
        Thread {
            try {
                val c = URL("https://api.ipify.org").openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                val ip = c.inputStream.bufferedReader().use { it.readText() }.trim()
                c.disconnect()
                if (ip.length in 7..45) publicIp = ip
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
