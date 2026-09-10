package com.feri.vrmirror.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var videoText: TextView
    private lateinit var accessibilityText: TextView
    private lateinit var startStopButton: Button
    private lateinit var accessibilityButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 1000)
        }
    }

    /** A képernyőrögzítés rendszer-engedélykérésének eredménye. */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, MirrorService::class.java).apply {
                    action = MirrorService.ACTION_START
                    putExtra(MirrorService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(MirrorService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, intent)
            } else {
                Toast.makeText(this, R.string.toast_projection_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Akár megadta, akár nem, a megosztás mehet (értesítés nélkül is fut).
            requestProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        ipText = findViewById(R.id.ipText)
        videoText = findViewById(R.id.videoText)
        accessibilityText = findViewById(R.id.accessibilityText)
        startStopButton = findViewById(R.id.startStopButton)
        accessibilityButton = findViewById(R.id.accessibilityButton)

        startStopButton.setOnClickListener {
            if (MirrorService.isRunning && MirrorService.isCapturing) {
                startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
            } else {
                startFlow()
            }
        }
        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private var autoRequested = false

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
        // Ha a rögzítés leállt (pl. képernyőzár), az értesítésre koppintva rögtön kérjük újra.
        if (MirrorService.isRunning && !MirrorService.isCapturing && !autoRequested) {
            autoRequested = true
            requestProjection()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun startFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun updateUi() {
        val running = MirrorService.isRunning
        val capturing = MirrorService.isCapturing
        val client = MirrorService.clientAddress
        if (capturing) autoRequested = false
        statusText.text = when {
            !running -> getString(R.string.status_stopped)
            !capturing -> getString(R.string.status_paused)
            client == null -> getString(R.string.status_waiting)
            else -> getString(R.string.status_connected, client)
        }
        ipText.text = getString(R.string.ip_label, NetUtils.localIp() ?: getString(R.string.ip_none))
        videoText.text = if (capturing) getString(R.string.video_label, MirrorService.videoInfo) else ""
        startStopButton.setText(
            when {
                !running -> R.string.btn_start
                !capturing -> R.string.btn_restart
                else -> R.string.btn_stop
            }
        )

        val accessibilityOn = TouchInjectorService.instance != null
        accessibilityText.setText(if (accessibilityOn) R.string.accessibility_on else R.string.accessibility_off)
    }
}
