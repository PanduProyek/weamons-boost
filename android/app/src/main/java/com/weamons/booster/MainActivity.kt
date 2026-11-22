package com.weamons.booster

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var statsBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var items: List<Pair<ResolveInfo, String>>
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#090711")
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(36), dp(20), dp(28))
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this)
        title.text = "WEAMONS BOOST"
        title.setTextColor(Color.parseColor("#B77AFF"))
        title.textSize = 28f
        title.gravity = Gravity.CENTER
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        root.addView(title)

        val sub = TextView(this)
        sub.text = "ULTIMATE GAMING COMPANION"
        sub.setTextColor(Color.parseColor("#8D84A8"))
        sub.textSize = 12f
        sub.gravity = Gravity.CENTER
        root.addView(sub)

        // ---------- IZIN ----------
        val permCard = card()
        permCard.addView(label("IZIN"))
        statusView = TextView(this)
        statusView.setTextColor(Color.WHITE)
        statusView.textSize = 14f
        statusView.setPadding(0, dp(8), 0, 0)
        permCard.addView(statusView)
        permCard.addView(button("Izinkan tampil di atas aplikasi lain") { openOverlaySettings() })
        permCard.addView(button("Izinkan akses Jangan Ganggu") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        permCard.addView(button("Izinkan atur kecerahan layar") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        permCard.addView(button("Buka layanan Aksesibilitas (untuk Screenshot)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        permCard.addView(button("Buka info aplikasi (izinkan setelan terbatas)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        permCard.addView(small(
            "Screenshot: nyalakan layanan \"WEAMONS BOOST (screenshot)\" di Aksesibilitas. " +
                "Jika tombolnya abu-abu (Android 13+), buka Info aplikasi, tekan titik tiga di kanan atas, " +
                "pilih \"Izinkan setelan terbatas\", lalu ulangi."
        ))
        permCard.addView(button("Tes bilah (tanpa buka game)") { startBar(null) })
        permCard.addView(button("Hentikan bilah") {
            stopService(Intent(this, OverlayService::class.java))
        })
        root.addView(permCard)

        // ---------- PENGATURAN ----------
        val setCard = card()
        setCard.addView(label("PENGATURAN BILAH"))

        val sideBtn = button("") { }
        sideBtn.text = sideText()
        sideBtn.setOnClickListener {
            Prefs.setSide(this, 1 - Prefs.side(this))
            sideBtn.text = sideText()
        }
        setCard.addView(sideBtn)

        val scaleBtn = button("") { }
        scaleBtn.text = scaleText()
        scaleBtn.setOnClickListener {
            Prefs.setScale(this, (Prefs.scale(this) + 1) % 3)
            scaleBtn.text = scaleText()
        }
        setCard.addView(scaleBtn)

        val breaks = intArrayOf(0, 30, 45, 60)
        val breakBtn = button("") { }
        breakBtn.text = breakText()
        breakBtn.setOnClickListener {
            val i = breaks.indexOf(Prefs.breakMin(this))
            Prefs.setBreakMin(this, breaks[(i + 1) % breaks.size])
            breakBtn.text = breakText()
        }
        setCard.addView(breakBtn)

        val profBtn = button("") { }
        profBtn.text = profileText()
        profBtn.setOnClickListener {
            val next = Prefs.startProfile(this) + 1
            Prefs.setStartProfile(this, if (next > 2) -1 else next)
            profBtn.text = profileText()
        }
        setCard.addView(profBtn)

        setCard.addView(small("Transparansi bilah"))
        val alphaBar = SeekBar(this)
        alphaBar.max = 70
        alphaBar.progress = Prefs.alpha(this) - 30
        alphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                Prefs.setAlpha(this@MainActivity, p + 30)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        setCard.addView(alphaBar)

        setCard.addView(toggle("Ping tampil di tab bilah", Prefs.hud(this)) { Prefs.setHud(this, it) })
        setCard.addView(
            toggle("Peringatan HP panas dan baterai rendah", Prefs.tempAlert(this)) {
                Prefs.setTempAlert(this, it)
            }
        )
        setCard.addView(
            toggle("Jangan Ganggu otomatis saat main", Prefs.autoDnd(this)) {
                Prefs.setAutoDnd(this, it)
            }
        )
        setCard.addView(small("Ukuran dan posisi berlaku saat bilah dimulai ulang. Bilah juga bisa ditarik ke pinggir mana saja."))
        root.addView(setCard)

        // ---------- PANDUAN ----------
        val guideCard = card()
        guideCard.addView(label("PANDUAN FITUR"))
        guideCard.addView(small(
            "PROFIL PERFORMA\n" +
                "Hemat: kecerahan rendah, sinkron latar mati, Jangan Ganggu aktif.\n" +
                "Seimbang: kembali ke pengaturan awal.\n" +
                "Pro: sinkron latar mati, Jangan Ganggu aktif, RAM dibersihkan, layar tidak mati. " +
                "Profil tidak menaikkan kecepatan CPU. Semua pengaturan dikembalikan saat bilah ditutup."
        ))
        guideCard.addView(small(
            "REKAM LAYAR\n" +
                "Tombol Rekam di bilah meminta izin sistem, lalu merekam layar tanpa suara. " +
                "Hasil ada di galeri, folder Movies/WEAMONS."
        ))
        guideCard.addView(small(
            "CHAT MENGAMBANG\n" +
                "Tombol WA/Discord membuka aplikasinya dalam jendela kecil. Ini hanya jalan jika HP mendukung " +
                "jendela freeform (Opsi pengembang: Aktifkan jendela freeform). Jika tidak, chat terbuka layar penuh."
        ))
        guideCard.addView(small(
            "KUNCI NAVIGASI (ANTI TERTEKAN)\n" +
                "Aplikasi biasa tidak bisa memblokir tombol Home/Back/Recent milik sistem. Cara resmi yang bisa dipakai: " +
                "Sematkan layar (Pin windows). Nyalakan di Pengaturan > Keamanan, lalu buka Recent, ketuk ikon game, " +
                "pilih Sematkan. Untuk melepas, tahan Back + Recent. Game Booster bawaan HP biasanya punya opsi blokir gestur juga."
        ))
        guideCard.addView(button("Buka pengaturan keamanan (Sematkan layar)") {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        })
        root.addView(guideCard)

        // ---------- STATISTIK ----------
        val statCard = card()
        statCard.addView(label("STATISTIK MAIN"))
        statsBox = LinearLayout(this)
        statsBox.orientation = LinearLayout.VERTICAL
        statCard.addView(statsBox)
        root.addView(statCard)

        // ---------- PILIH GAME ----------
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        items = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { Pair(it, it.loadLabel(pm).toString()) }

        val gameCard = card()
        gameCard.addView(label("PILIH GAME"))
        gameCard.addView(small("Ketuk untuk membuka game dengan bilah aktif. Tahan lama untuk menandai favorit."))
        val search = EditText(this)
        search.hint = "Cari aplikasi..."
        search.setHintTextColor(Color.parseColor("#8D84A8"))
        search.setTextColor(Color.WHITE)
        search.textSize = 14f
        search.setSingleLine(true)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                renderApps()
            }
        })
        gameCard.addView(search)
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        gameCard.addView(listBox)
        root.addView(gameCard)

        renderApps()

        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        renderStats()
    }

    private fun sideText(): String =
        "Posisi bilah: " + (if (Prefs.side(this) == 0) "Kiri" else "Kanan")

    private fun scaleText(): String {
        val names = arrayOf("Kecil", "Sedang", "Besar")
        return "Ukuran bilah: " + names[Prefs.scale(this)]
    }

    private fun profileText(): String {
        val names = arrayOf("Hemat Baterai", "Seimbang", "Performa Tinggi")
        val p = Prefs.startProfile(this)
        return "Profil saat game mulai: " + (if (p in 0..2) names[p] else "Tidak ada")
    }

    private fun breakText(): String {
        val m = Prefs.breakMin(this)
        return "Pengingat istirahat: " + (if (m == 0) "Mati" else "$m menit")
    }

    private fun refreshStatus() {
        val nm = getSystemService(NotificationManager::class.java)
        val overlay = Settings.canDrawOverlays(this)
        val dnd = nm.isNotificationPolicyAccessGranted
        val write = Settings.System.canWrite(this)
        val o = if (overlay) "aktif" else "BELUM (wajib)"
        val d = if (dnd) "aktif" else "belum (opsional)"
        val w = if (write) "aktif" else "belum (opsional)"
        val a = if (BoosterAccessibilityService.isEnabled(this)) "aktif" else "belum (untuk Screenshot)"
        statusView.text = "Tampil di atas aplikasi lain: $o\n" +
            "Akses Jangan Ganggu: $d\n" +
            "Atur kecerahan layar: $w\n" +
            "Layanan Screenshot (Aksesibilitas): $a"
    }

    private fun renderStats() {
        statsBox.removeAllViews()
        val stats = Prefs.todayStats(this)
        if (stats.isEmpty()) {
            statsBox.addView(small("Belum ada sesi main hari ini."))
        } else {
            var total = 0L
            for ((pkg, sec) in stats) {
                total += sec
                val name = try {
                    packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
                } catch (e: Exception) {
                    pkg
                }
                val t = TextView(this)
                t.text = name + ": " + Prefs.fmt(sec)
                t.setTextColor(Color.WHITE)
                t.textSize = 14f
                t.setPadding(0, dp(4), 0, 0)
                statsBox.addView(t)
            }
            statsBox.addView(small("Total hari ini: " + Prefs.fmt(total)))
        }
        statsBox.addView(small("7 hari terakhir: " + Prefs.fmt(Prefs.periodSeconds(this, 7))))
    }

    private fun renderApps() {
        val favs = Prefs.favs(this)
        val q = query.trim().lowercase()
        val shown = items
            .filter { q.isEmpty() || it.second.lowercase().contains(q) }
            .sortedWith(
                compareBy<Pair<ResolveInfo, String>>(
                    { !favs.contains(it.first.activityInfo.packageName) },
                    { it.second.lowercase() }
                )
            )
        listBox.removeAllViews()
        val pm = packageManager
        for (item in shown) {
            val ri = item.first
            val pkg = ri.activityInfo.packageName
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(10), dp(8), dp(10))

            val icon = ImageView(this)
            icon.setImageDrawable(ri.loadIcon(pm))
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))

            val name = TextView(this)
            name.text = (if (favs.contains(pkg)) "★ " else "") + item.second
            name.setTextColor(Color.WHITE)
            name.textSize = 15f
            name.setPadding(dp(12), 0, 0, 0)
            row.addView(name)

            row.setOnClickListener { startBar(pkg) }
            row.setOnLongClickListener {
                Prefs.toggleFav(this, pkg)
                renderApps()
                true
            }
            listBox.addView(row)
        }
    }

    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(i)
    }

    private fun startBar(pkg: String?) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan dulu tampil di atas aplikasi lain", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        val svc = Intent(this, OverlayService::class.java)
        if (pkg != null) svc.putExtra("pkg", pkg)
        startForegroundService(svc)
        if (pkg != null) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                Toast.makeText(this, "Aplikasi tidak bisa dibuka", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(launch)
        } else {
            Toast.makeText(this, "Bilah aktif di pinggir layar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(16), dp(16), dp(16))
        val shape = GradientDrawable()
        shape.setColor(Color.parseColor("#211831"))
        shape.cornerRadius = dp(16).toFloat()
        c.background = shape
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(16)
        c.layoutParams = lp
        return c
    }

    private fun label(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextColor(Color.WHITE)
        t.textSize = 15f
        t.setTypeface(null, android.graphics.Typeface.BOLD)
        return t
    }

    private fun small(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextColor(Color.parseColor("#8D84A8"))
        t.textSize = 12f
        t.setPadding(0, dp(6), 0, dp(4))
        return t
    }

    private fun toggle(s: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val sw = Switch(this)
        sw.text = s
        sw.setTextColor(Color.WHITE)
        sw.textSize = 14f
        sw.isChecked = checked
        sw.setPadding(0, dp(10), 0, dp(4))
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        return sw
    }

    private fun button(s: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = s
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        val shape = GradientDrawable()
        shape.setColor(Color.parseColor("#902DE0"))
        shape.cornerRadius = dp(10).toFloat()
        b.background = shape
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(10)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }
}
