package com.feri.vrmirror.quest

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.feri.vrmirror.common.Protocol

class MainActivity : AppCompatActivity(), StreamClient.Listener, SurfaceHolder.Callback {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS = "vrmirror"
        private const val PREF_IP = "ip"
    }

    private lateinit var ipInput: EditText
    private lateinit var connectButton: Button
    private lateinit var searchButton: Button
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var videoContainer: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView

    private var client: StreamClient? = null

    // A dekóderhez tartozó állapot – a "decoderLock" védi, mert több szálról érjük el.
    private val decoderLock = Any()
    private var decoder: VideoDecoder? = null
    private var surface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var codecConfig: ByteArray? = null
    private var waitingForKeyframe = true
    private var lastKeyframeRequest = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ipInput)
        connectButton = findViewById(R.id.connectButton)
        searchButton = findViewById(R.id.searchButton)
        statusText = findViewById(R.id.statusText)
        hintText = findViewById(R.id.hintText)
        videoContainer = findViewById(R.id.videoContainer)
        surfaceView = findViewById(R.id.surfaceView)

        ipInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_IP, ""))

        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { v, event -> handleTouch(v, event) }

        connectButton.setOnClickListener {
            if (client != null) disconnect() else connect(ipInput.text.toString().trim())
        }
        searchButton.setOnClickListener { search() }

        findViewById<Button>(R.id.backButton).setOnClickListener { client?.sendKey(Protocol.KEY_BACK) }
        findViewById<Button>(R.id.homeButton).setOnClickListener { client?.sendKey(Protocol.KEY_HOME) }
        findViewById<Button>(R.id.recentsButton).setOnClickListener { client?.sendKey(Protocol.KEY_RECENTS) }
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    // ---- Kapcsolat ----

    private fun connect(ip: String) {
        if (ip.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_ip, Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_IP, ip).apply()
        client?.close()
        client = StreamClient(ip, this).also { it.start() }
        connectButton.setText(R.string.btn_disconnect)
        setStatus(getString(R.string.status_connecting, ip))
    }

    private fun disconnect() {
        client?.close()
        client = null
        synchronized(decoderLock) {
            decoder?.release()
            decoder = null
            codecConfig = null
            waitingForKeyframe = true
        }
        connectButton.setText(R.string.btn_connect)
        hintText.visibility = View.GONE
        setStatus(getString(R.string.status_idle))
    }

    private fun search() {
        searchButton.isEnabled = false
        setStatus(getString(R.string.status_searching))
        Thread {
            val ip = Discovery.find()
            runOnUiThread {
                searchButton.isEnabled = true
                if (ip != null) {
                    ipInput.setText(ip)
                    setStatus(getString(R.string.status_found, ip))
                    connect(ip)
                } else {
                    setStatus(getString(R.string.status_not_found))
                }
            }
        }.start()
    }

    private fun setStatus(text: String?) {
        runOnUiThread {
            if (text == null) {
                statusText.visibility = View.GONE
            } else {
                statusText.text = text
                statusText.visibility = View.VISIBLE
            }
        }
    }

    // ---- Érintés továbbítása ----

    private fun handleTouch(v: View, event: MotionEvent): Boolean {
        val c = client ?: return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> Protocol.TOUCH_DOWN
            MotionEvent.ACTION_MOVE -> Protocol.TOUCH_MOVE
            MotionEvent.ACTION_UP -> Protocol.TOUCH_UP
            MotionEvent.ACTION_CANCEL -> Protocol.TOUCH_CANCEL
            else -> return true
        }
        if (v.width == 0 || v.height == 0) return true
        // Csak az első ujjat/mutatót követjük.
        val nx = (event.getX(0) / v.width).coerceIn(0f, 1f)
        val ny = (event.getY(0) / v.height).coerceIn(0f, 1f)
        c.sendTouch(action, nx, ny)
        return true
    }

    // ---- StreamClient.Listener (hálózati szálon) ----

    override fun onConnected() {
        setStatus(getString(R.string.status_connected))
    }

    override fun onDisconnected(reason: String) {
        synchronized(decoderLock) {
            codecConfig = null
            waitingForKeyframe = true
        }
        runOnUiThread { hintText.visibility = View.GONE }
        setStatus(getString(R.string.status_disconnected, reason))
    }

    override fun onStatus(flags: Int) {
        val capturing = flags and Protocol.STATUS_FLAG_CAPTURING != 0
        val touchEnabled = flags and Protocol.STATUS_FLAG_TOUCH_ENABLED != 0
        Log.i(TAG, "Telefon állapot: rögzítés=$capturing érintés=$touchEnabled")
        if (!capturing) {
            synchronized(decoderLock) {
                codecConfig = null
                waitingForKeyframe = true
            }
            setStatus(getString(R.string.status_capture_stopped))
        }
        runOnUiThread { hintText.visibility = if (touchEnabled) View.GONE else View.VISIBLE }
    }

    override fun onVideoConfig(width: Int, height: Int) {
        Log.i(TAG, "Videó méret: ${width}x${height}")
        synchronized(decoderLock) {
            videoWidth = width
            videoHeight = height
            codecConfig = null
            waitingForKeyframe = true
            recreateDecoderLocked()
        }
        runOnUiThread { videoContainer.setAspectRatio(width.toFloat() / height.toFloat()) }
    }

    override fun onCodecConfig(data: ByteArray) {
        synchronized(decoderLock) {
            codecConfig = data
            waitingForKeyframe = true
            // Ha a dekóder már kapott korábban configot, a kódoló újraindult: friss dekóder kell.
            if (decoder?.hasCodecConfig == true || decoder == null) recreateDecoderLocked()
            decoder?.feed(data, 0, data.size, 0, codecConfig = true)
        }
    }

    override fun onFrame(data: ByteArray, offset: Int, length: Int, keyframe: Boolean, ptsUs: Long) {
        synchronized(decoderLock) {
            val d = decoder ?: return
            if (!d.hasCodecConfig) return
            if (waitingForKeyframe) {
                if (!keyframe) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastKeyframeRequest > 1000) {
                        lastKeyframeRequest = now
                        client?.requestKeyframe()
                    }
                    return
                }
                waitingForKeyframe = false
                setStatus(null)
            }
            d.feed(data, offset, length, ptsUs, codecConfig = false)
        }
    }

    // ---- Dekóder kezelése ----

    /** Csak decoderLock alatt hívható. */
    private fun recreateDecoderLocked() {
        decoder?.release()
        decoder = null
        val s = surface ?: return
        if (videoWidth <= 0 || videoHeight <= 0) return
        try {
            decoder = VideoDecoder(videoWidth, videoHeight, s) { e ->
                Log.e(TAG, "Dekóder hiba, újraindítás: ${e.message}")
                Thread {
                    synchronized(decoderLock) {
                        waitingForKeyframe = true
                        recreateDecoderLocked()
                        codecConfig?.let { cfg -> decoder?.feed(cfg, 0, cfg.size, 0, codecConfig = true) }
                    }
                    client?.requestKeyframe()
                }.start()
            }
            waitingForKeyframe = true
        } catch (e: Exception) {
            Log.e(TAG, "Dekóder létrehozás sikertelen: ${e.message}", e)
        }
    }

    // ---- SurfaceHolder.Callback (UI szálon) ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(decoderLock) {
            surface = holder.surface
            recreateDecoderLocked()
            codecConfig?.let { cfg -> decoder?.feed(cfg, 0, cfg.size, 0, codecConfig = true) }
        }
        client?.requestKeyframe()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(decoderLock) {
            decoder?.release()
            decoder = null
            surface = null
        }
    }
}
