package com.example.screencast

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        status = TextView(this).apply { textSize = 18f; text = "Connect phone and TV to the same Wi-Fi." }
        val start = Button(this).apply {
            text = "Start casting"
            setOnClickListener {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), 1)
            }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, CastService::class.java)); status.text = "Stopped." }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48)
            addView(status); addView(start); addView(stop)
        })
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK && data != null) {
            val i = Intent(this, CastService::class.java).putExtra("code", res).putExtra("data", data)
            startForegroundService(i)
            status.text = "On your TV's browser, open:\n\nhttp://${localIp()}:8080"
        }
    }

    private fun localIp(): String =
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "?"
}
