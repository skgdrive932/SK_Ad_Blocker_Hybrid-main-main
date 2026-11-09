package com.skkaushal.adblocker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val uiHandler = Handler(Looper.getMainLooper())
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startBlocker()
        else showStopped("VPN permission was not granted.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        detail = findViewById(R.id.detailText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        findViewById<Button>(R.id.aboutButton).setOnClickListener {
            findViewById<TextView>(R.id.aboutText).visibility = android.view.View.VISIBLE
        }
        startButton.setOnClickListener {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) vpnPermission.launch(prepareIntent) else startBlocker()
        }
        stopButton.setOnClickListener { stopBlocker() }
        requestNotificationPermissionIfNeeded()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 700)
        }
    }

    private fun startBlocker() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, AdBlockVpnService::class.java))
            status.text = "Protection starting…"
            detail.text = "Only DNS traffic is filtered. Other internet traffic stays on your normal connection."
            startButton.isEnabled = false
            stopButton.isEnabled = true
            uiHandler.postDelayed({ refreshUi() }, 700)
            uiHandler.postDelayed({ refreshUi() }, 1600)
        } catch (e: Exception) {
            showStopped("Could not start protection: ${e.localizedMessage ?: "unknown error"}")
        }
    }

    private fun refreshUi() {
        if (AdBlockVpnService.running) {
            status.text = "PROTECTION ACTIVE"
            detail.text = "DNS ad filtering is running. Wi-Fi and mobile data traffic outside DNS continue normally."
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } else {
            startButton.isEnabled = true
            stopButton.isEnabled = true
            showStopped("Protection is off. Your phone uses its normal network settings.")
        }
    }

    private fun showStopped(message: String) {
        status.text = "PROTECTION OFF"
        detail.text = message
        startButton.isEnabled = true
        stopButton.isEnabled = true
    }

    private fun stopBlocker() {
        status.text = "STOPPING…"
        detail.text = "Disconnecting the local VPN and restoring normal network routing."
        setControlsEnabled(false)
        try {
            startService(Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP))
        } catch (e: Exception) {
            detail.text = "Stop request failed: ${e.localizedMessage ?: "unknown error"}"
        }
        uiHandler.postDelayed({ refreshUi() }, 400)
        uiHandler.postDelayed({ refreshUi() }, 1200)
        uiHandler.postDelayed({ refreshUi() }, 2200)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        startButton.isEnabled = enabled && !AdBlockVpnService.running
        // Keep Stop available during startup as well, so a slow VPN establish can be cancelled.
        stopButton.isEnabled = enabled
    }
}
