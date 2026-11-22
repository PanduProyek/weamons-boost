package com.weamons.booster

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

// Activity transparan: hanya untuk meminta izin rekam layar, lalu menyerahkannya ke RecorderService.
class ProjectionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), 100)
        } catch (e: Exception) {
            Toast.makeText(this, "Rekam layar tidak tersedia", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecorderService::class.java)
            i.action = "START"
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
        } else {
            Toast.makeText(this, "Perekaman dibatalkan", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
