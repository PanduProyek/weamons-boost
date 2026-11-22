package com.weamons.booster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

// Net Blocker: VPN lokal yang membuang semua lalu lintas aplikasi lain, kecuali game terpilih
// dan WEAMONS sendiri. Tidak ada data yang keluar dari HP lewat VPN ini.
class NetBlockService : VpnService() {

    companion object {
        @Volatile
        var active = false
    }

    private var tun: ParcelFileDescriptor? = null
    private var thread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopBlock()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(2, notification())
        if (tun == null) startBlock()
        return START_NOT_STICKY
    }

    private fun startBlock() {
        try {
            val b = Builder()
                .setSession("WEAMONS Net Blocker")
                .addAddress("10.77.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:77::2", 128)
                .addRoute("::", 0)
                .setMtu(1500)
            try {
                b.addDisallowedApplication(packageName)
            } catch (e: Exception) {
            }
            val game = Prefs.boostPkg(this)
            if (game != null) {
                try {
                    b.addDisallowedApplication(game)
                } catch (e: Exception) {
                }
            }
            val fd = b.establish()
            if (fd == null) {
                stopSelf()
                return
            }
            tun = fd
            active = true
            val t = Thread {
                // baca dan buang semua paket: aplikasi yang diblokir tidak punya internet
                val input = FileInputStream(fd.fileDescriptor)
                val buf = ByteArray(32767)
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        if (input.read(buf) < 0) break
                    }
                } catch (e: Exception) {
                }
            }
            thread = t
            t.start()
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun stopBlock() {
        active = false
        thread?.interrupt()
        thread = null
        try {
            tun?.close()
        } catch (e: Exception) {
        }
        tun = null
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("netblock", "Net Blocker", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = Intent(this, NetBlockService::class.java)
        stop.action = "STOP"
        val pi = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "netblock")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Net Blocker aktif")
            .setContentText("Internet aplikasi lain diblokir. Ketuk untuk menghentikan.")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        stopBlock()
        stopSelf()
    }

    override fun onDestroy() {
        stopBlock()
        super.onDestroy()
    }
}
