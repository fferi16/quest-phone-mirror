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

        private const val JOY_DEADZONE = 0.25f
        private const val JOY_INTERVAL_MS = 90L
        /** Teljesen kitolt karral ennyi "fokot" görget egy lépésben. */
        private const val JOY_STEP = 0.5f
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
        if (action != Protocol.TOUCH_MOVE) {
            Log.d(TAG, "Érintés: action=$action x=$nx y=$ny forrás=${event.source}")
        }
        c.sendTouch(action, nx, ny)
        return true
    }

    // ---- Görgetés: egérgörgő-események és a kontroller hüvelykujj-karja ----

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var joyX = 0f
    private var joyY = 0f
    private var joyRunning = false
    private var lastPointerNx = 0.5f
    private var lastPointerNy = 0.5f

    private val joyTick = object : Runnable {
        override fun run() {
            val c = client
            val ax = if (kotlin.math.abs(joyX) > JOY_DEADZONE) joyX else 0f
            val ay = if (kotlin.math.abs(joyY) > JOY_DEADZONE) joyY else 0f
            if (c == null || (ax == 0f && ay == 0f)) {
                joyRunning = false
                return
            }
            // Kar felfelé (ay < 0) = a tartalom felfelé gördül = görgő felfelé (dy > 0).
            c.sendScroll(lastPointerNx, lastPointerNy, ax * JOY_STEP, -ay * JOY_STEP)
            uiHandler.postDelayed(this, JOY_INTERVAL_MS)
        }
    }

    /** A képernyőpozíció (ablak-koordináta) átváltása a videó 0..1 arányos koordinátáira. */
    private fun rememberPointer(event: MotionEvent) {
        val loc = IntArray(2)
        surfaceView.getLocationInWindow(loc)
        if (surfaceView.width == 0 || surfaceView.height == 0) return
        val nx = (event.x - loc[0]) / surfaceView.width
        val ny = (event.y - loc[1]) / surfaceView.height
        if (nx in 0f..1f && ny in 0f..1f) {
            lastPointerNx = nx
            lastPointerNy = ny
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val c = client
        if (c != null) {
            val source = ev.source
            when {
                ev.actionMasked == MotionEvent.ACTION_SCROLL -> {
                    rememberPointer(ev)
                    val dx = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    val dy = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    Log.d(TAG, "Görgő: dx=$dx dy=$dy forrás=$source")
                    if (dx != 0f || dy != 0f) c.sendScroll(lastPointerNx, lastPointerNy, dx, dy)
                    return true
                }
                source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK &&
                    ev.actionMasked == MotionEvent.ACTION_MOVE -> {
                    joyX = ev.getAxisValue(MotionEvent.AXIS_X)
                    joyY = ev.getAxisValue(MotionEvent.AXIS_Y)
                    if (joyX == 0f && joyY == 0f) {
                        // Néhány eszköz a HAT tengelyeken küldi
                        joyX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
                        joyY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
                    }
                    Log.d(TAG, "Joystick: x=$joyX y=$joyY forrás=$source")
                    if (!joyRunning && (kotlin.math.abs(joyX) > JOY_DEADZONE || kotlin.math.abs(joyY) > JOY_DEADZONE)) {
                        joyRunning = true
                        uiHandler.post(joyTick)
                    }
                    return true
                }
                ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE -> rememberPointer(ev)
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val c = client
        if (c != null && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val (dx, dy) = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> 0f to 1f
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 0f to -1f
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
                else -> 0f to 0f
            }
            if (dx != 0f || dy != 0f) {
                Log.d(TAG, "DPAD görgetés: dx=$dx dy=$dy")
                c.sendScroll(lastPointerNx, lastPointerNy, dx, dy)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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
