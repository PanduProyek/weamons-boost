package com.weamons.booster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

// Merekam layar (tanpa suara) lalu menyimpan ke Movies/WEAMONS.
class RecorderService : Service() {

    companion object {
        @Volatile
        var recording = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopRecording(true)
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startRecording(code, data)
        } catch (e: Exception) {
            Toast.makeText(this, "Rekam gagal: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            stopRecording(false)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("rec", "Rekam layar", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = Intent(this, RecorderService::class.java)
        stop.action = "STOP"
        val pi = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE)
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_launcher), "Berhenti", pi
        ).build()
        val n = Notification.Builder(this, "rec")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Merekam layar")
            .setContentText("Ketuk Berhenti untuk menyimpan")
            .addAction(action)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n)
        }
    }

    private fun openOutput(rec: MediaRecorder) {
        val name = "WEAMONS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues()
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name)
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WEAMONS")
            val u: Uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("gagal membuat file")
            val p = contentResolver.openFileDescriptor(u, "w")
                ?: throw IllegalStateException("gagal membuka file")
            pfd = p
            rec.setOutputFile(p.fileDescriptor)
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            rec.setOutputFile(File(dir, name).absolutePath)
        }
    }

    private fun startRecording(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post {
                    if (recording) stopRecording(true)
                    stopSelf()
                }
            }
        }, handler)
        projection = proj

        val dm = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(dm)
        var w = dm.widthPixels
        var h = dm.heightPixels
        val maxDim = max(w, h)
        if (maxDim > 1920) {
            val f = 1920f / maxDim
            w = (w * f).toInt()
            h = (h * f).toInt()
        }
        w = w and 1.inv()
        h = h and 1.inv()

        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoSize(w, h)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate(8000000)
        openOutput(rec)
        rec.prepare()
        recorder = rec

        display = proj.createVirtualDisplay(
            "weamons-rec", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null
        )
        rec.start()
        recording = true
        Toast.makeText(this, "Mulai merekam (tanpa suara)", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording(notify: Boolean) {
        val was = recording
        recording = false
        try {
            recorder?.stop()
        } catch (e: Exception) {
        }
        try {
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null
        try {
            display?.release()
        } catch (e: Exception) {
        }
        display = null
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null
        try {
            pfd?.close()
        } catch (e: Exception) {
        }
        pfd = null
        if (notify && was) {
            Toast.makeText(this, "Rekaman disimpan di Movies/WEAMONS", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        if (recording) stopRecording(true)
        super.onDestroy()
    }
}
