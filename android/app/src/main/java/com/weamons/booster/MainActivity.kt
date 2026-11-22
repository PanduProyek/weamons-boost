package com.weamons.booster

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView

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

        val permCard = card()
        permCard.addView(label("IZIN"))
        statusView = TextView(this)
        statusView.setTextColor(Color.WHITE)
        statusView.textSize = 14f
        statusView.setPadding(0, dp(8), 0, 0)
        permCard.addView(statusView)
        permCard.addView(button("Izinkan tampil di atas aplikasi lain") { openOverlaySettings() })
        permCard.addView(button("Izinkan akses Jangan Ganggu (opsional)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        root.addView(permCard)

        val gameCard = card()
        gameCard.addView(label("PILIH GAME"))
        val hint = TextView(this)
        hint.text = "Ketuk game untuk membukanya dengan bilah samping aktif."
        hint.setTextColor(Color.parseColor("#8D84A8"))
        hint.textSize = 12f
        hint.setPadding(0, dp(6), 0, dp(6))
        gameCard.addView(hint)
        addApps(gameCard)
        root.addView(gameCard)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val nm = getSystemService(NotificationManager::class.java)
        val overlay = Settings.canDrawOverlays(this)
        val dnd = nm.isNotificationPolicyAccessGranted
        val o = if (overlay) "aktif" else "BELUM (wajib)"
        val d = if (dnd) "aktif" else "belum (opsional)"
        statusView.text = "Tampil di atas aplikasi lain: $o\nAkses Jangan Ganggu: $d"
    }

    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(i)
    }

    private fun addApps(box: LinearLayout) {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        for (ri in apps) {
            val pkg = ri.activityInfo.packageName
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(10), dp(8), dp(10))

            val icon = ImageView(this)
            icon.setImageDrawable(ri.loadIcon(pm))
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))

            val name = TextView(this)
            name.text = ri.loadLabel(pm)
            name.setTextColor(Color.WHITE)
            name.textSize = 15f
            name.setPadding(dp(12), 0, 0, 0)
            row.addView(name)

            row.setOnClickListener { startGame(pkg) }
            box.addView(row)
        }
    }

    private fun startGame(pkg: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan dulu tampil di atas aplikasi lain", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Toast.makeText(this, "Aplikasi tidak bisa dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = Intent(this, OverlayService::class.java)
        svc.putExtra("pkg", pkg)
        startForegroundService(svc)
        startActivity(launch)
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
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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

    private fun button(s: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = s
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        val shape = GradientDrawable()
        shape.setColor(Color.parseColor("#902DE0"))
        shape.cornerRadius = dp(10).toFloat()
        b.background = shape
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }
}
