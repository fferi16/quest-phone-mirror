package com.feri.vrmirror.phone

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import com.feri.vrmirror.common.Protocol

/**
 * Előtérben futó szolgáltatás: képernyőrögzítés + kódolás + hálózati szerver.
 *
 * A szerver addig fut, amíg a felhasználó le nem állítja. A képernyőrögzítést
 * a rendszer magától is leállíthatja (pl. képernyőzár) – ilyenkor a szolgáltatás
 * életben marad, és az Activity-ből újra elkérhető az engedély.
 */
class MirrorService : Service() {

    companion object {
        private const val TAG = "MirrorService"
        const val ACTION_START = "com.feri.vrmirror.phone.START"
        const val ACTION_STOP = "com.feri.vrmirror.phone.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "mirror"
        private const val NOTIFICATION_ID = 1

        @Volatile var instance: MirrorService? = null
            private set

        // Állapot az Activity-nek (egyszerű, lekérdezéses megoldás).
        @Volatile var isRunning = false
            private set
        @Volatile var isCapturing = false
            private set
        @Volatile var clientAddress: String? = null
            private set
        @Volatile var videoInfo: String = ""
            private set

        private const val PREFS = "vrmirror"
        private const val PREF_MUTE = "mute"

        fun isMuteEnabled(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_MUTE, true)

        fun setMuteEnabled(ctx: android.content.Context, enabled: Boolean) {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_MUTE, enabled).apply()
            instance?.let { s -> s.mainHandler.post { s.updateMute() } }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    private var server: NetServer? = null
    private var discovery: DiscoveryResponder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audio: AudioCapturer? = null
    /** A némítás előtti médiahangerő, ha éppen némítva van. */
    private var savedVolume: Int? = null

    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        createNotificationChannel()
        // Android 14+: a mediaProjection típusú előtér kötelező a getMediaProjection előtt.
        startForegroundCompat(buildNotification(paused = false))

        if (!isRunning) {
            try {
                server = NetServer(serverListener).also { it.start() }
                discovery = DiscoveryResponder().also { it.start() }
            } catch (e: Exception) {
                Log.e(TAG, "Szerver indítási hiba: ${e.message}", e)
                stopSelf()
                return
            }
            isRunning = true
        }

        if (projection != null) {
            // Már fut a rögzítés, nincs teendő.
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Nincs érvényes képernyőrögzítési engedély")
            pushStatus()
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection hiba: ${e.message}")
            null
        }
        if (mp == null) {
            pushStatus()
            return
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "A rendszer leállította a képernyőrögzítést")
                mainHandler.post { onProjectionStopped() }
            }
        }, mainHandler)
        projection = mp

        try {
            setupCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Rögzítés indítási hiba: ${e.message}", e)
            stopCapture()
            return
        }
        isCapturing = true
        pushStatus()
        updateWakeLock()
        updateMute()
    }

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasAudioPermission()) {
                // A lejátszott hang elkapásához Android 14+ ezt a típust is kéri.
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                Log.w(TAG, "Előtér indítása mikrofon típussal sikertelen, csak mediaProjection: ${e.message}")
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- Hang ----

    private val audioListener = object : AudioCapturer.Listener {
        override fun onAudioConfig(sampleRate: Int, channels: Int) {
            server?.setAudioConfig(sampleRate, channels)
        }

        override fun onAudio(data: ByteArray, length: Int) {
            server?.sendAudio(data, length)
        }
    }

    private fun startAudio(mp: MediaProjection) {
        if (audio != null || !hasAudioPermission()) return
        try {
            audio = AudioCapturer(mp, audioListener).also { it.start() }
            Log.i(TAG, "Hangátvitel elindult")
        } catch (e: Exception) {
            Log.w(TAG, "Hangátvitel nem indítható: ${e.message}")
            audio = null
        }
    }

    private fun stopAudio() {
        audio?.stop()
        audio = null
        server?.clearAudio()
    }

    /** Amíg a Quest csatlakozva van és fut a rögzítés, a telefon médiahangja némítva (ha be van kapcsolva). */
    private fun updateMute() {
        val needed = isCapturing && clientAddress != null && isMuteEnabled(this)
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        try {
            if (needed && savedVolume == null) {
                savedVolume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
                Log.i(TAG, "Telefon némítva (előző hangerő: $savedVolume)")
            } else if (!needed && savedVolume != null) {
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, savedVolume!!, 0)
                Log.i(TAG, "Telefon hangereje visszaállítva: $savedVolume")
                savedVolume = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hangerő állítás sikertelen: ${e.message}")
        }
    }

    /** Valós képernyőméret és dpi. */
    private fun screenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display: Display? = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics) ?: metrics.setTo(resources.displayMetrics)
        return metrics
    }

    /**
     * Létrehozza (vagy elforgatás után újra beállítja) a kódolót és a virtuális kijelzőt.
     * Android 14-en egy MediaProjection-höz csak egyszer hozható létre virtuális kijelző,
     * ezért elforgatáskor nem újat készítünk, hanem átméretezzük és új Surface-t adunk neki.
     */
    private fun setupCapture() {
        val mp = projection ?: return
        val metrics = screenMetrics()
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        val (ew, eh) = ScreenEncoder.chooseSize(screenW, screenH)
        Log.i(TAG, "Képernyő ${screenW}x${screenH} -> videó ${ew}x${eh}")

        server?.setVideoConfig(ew, eh)

        val newEncoder = ScreenEncoder(ew, eh, encoderListener)
        newEncoder.start()

        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = mp.createVirtualDisplay(
                "VRMirror", ew, eh, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newEncoder.inputSurface, null, mainHandler
            )
        } else {
            vd.resize(ew, eh, metrics.densityDpi)
            vd.surface = newEncoder.inputSurface
        }

        val old = encoder
        encoder = newEncoder
        old?.stop()

        videoInfo = "${ew}×${eh}, ${ScreenEncoder.BITRATE / 1_000_000} Mbit/s"
        startAudio(mp)
    }

    /** A rendszer (pl. képernyőzár) leállította a rögzítést: a szerver marad, a rögzítés nem. */
    private fun onProjectionStopped() {
        stopCapture()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(paused = true))
    }

    private fun stopCapture() {
        isCapturing = false
        stopAudio()
        encoder?.stop()
        encoder = null
        virtualDisplay?.release()
        virtualDisplay = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        videoInfo = ""
        server?.clearVideo()
        pushStatus()
        updateWakeLock()
        updateMute()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isCapturing) return
        val m = screenMetrics()
        if (m.widthPixels != screenW || m.heightPixels != screenH) {
            Log.i(TAG, "Elforgatás észlelve, kódoló újraindítása")
            try {
                setupCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Újraindítás sikertelen: ${e.message}", e)
            }
        }
    }

    // ---- Állapot a Quest felé ----

    /** Elküldi a Questnek, hogy fut-e a rögzítés és engedélyezve van-e az érintésvezérlés. */
    fun pushStatus() {
        var flags = 0
        if (isCapturing) flags = flags or Protocol.STATUS_FLAG_CAPTURING
        if (TouchInjectorService.instance != null) flags = flags or Protocol.STATUS_FLAG_TOUCH_ENABLED
        server?.setStatus(flags)
    }

    // ---- Ébren tartás ----

    /**
     * Amíg a Quest csatlakozva van és fut a rögzítés, a telefon képernyője ne zárolódjon,
     * különben a rendszer leállítja a rögzítést. Elsötétített módban tartja a kijelzőt.
     */
    private fun updateWakeLock() {
        val needed = isCapturing && clientAddress != null
        val current = wakeLock
        if (needed && current == null) {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "VRMirror:screen"
            )
            wl.acquire()
            wakeLock = wl
            Log.i(TAG, "Képernyő ébren tartása bekapcsolva")
        } else if (!needed && current != null) {
            try {
                current.release()
            } catch (_: Exception) {
            }
            wakeLock = null
            Log.i(TAG, "Képernyő ébren tartása kikapcsolva")
        }
    }

    // ---- Kódoló és szerver események ----

    private val encoderListener = object : ScreenEncoder.Listener {
        override fun onCodecConfig(data: ByteArray) {
            server?.setCodecConfig(data)
        }

        override fun onFrame(data: ByteArray, keyframe: Boolean, ptsUs: Long) {
            server?.sendFrame(data, keyframe, ptsUs)
        }
    }

    private var warnedNoTouch = false

    private val serverListener = object : NetServer.Listener {
        override fun onClientConnected(address: String) {
            clientAddress = address
            warnedNoTouch = false
            encoder?.requestKeyframe()
            mainHandler.post {
                updateWakeLock()
                updateMute()
            }
        }

        override fun onClientDisconnected() {
            clientAddress = null
            mainHandler.post {
                updateWakeLock()
                updateMute()
            }
        }

        override fun onTouch(action: Int, x: Float, y: Float) {
            val injector = TouchInjectorService.instance
            if (injector == null) {
                if (!warnedNoTouch) {
                    warnedNoTouch = true
                    Log.w(TAG, "Érintés érkezett, de az érintésvezérlés nincs engedélyezve")
                }
                return
            }
            injector.injectTouch(action, x, y)
        }

        override fun onScroll(x: Float, y: Float, dx: Float, dy: Float) {
            TouchInjectorService.instance?.injectScroll(x, y, dx, dy)
        }

        override fun onPinch(action: Int, cx: Float, cy: Float, spread: Float) {
            TouchInjectorService.instance?.injectPinch(action, cx, cy, spread)
        }

        override fun onKey(key: Int) {
            TouchInjectorService.instance?.pressKey(key)
        }

        override fun onKeyframeRequested() {
            encoder?.requestKeyframe()
        }
    }

    override fun onDestroy() {
        isRunning = false
        clientAddress = null
        stopCapture()
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ---- Értesítés ----

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(paused: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(if (paused) R.string.notif_text_paused else R.string.notif_text))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
