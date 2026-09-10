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
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat

/**
 * Előtérben futó szolgáltatás: képernyőrögzítés + kódolás + hálózati szerver.
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

        // Állapot az Activity-nek (egyszerű, lekérdezéses megoldás).
        @Volatile var isRunning = false
            private set
        @Volatile var clientAddress: String? = null
            private set
        @Volatile var videoInfo: String = ""
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    private var server: NetServer? = null
    private var discovery: DiscoveryResponder? = null

    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (isRunning) return

        createNotificationChannel()
        val notification = buildNotification()
        // Android 14+: a mediaProjection típusú előtér indítása kötelező a getMediaProjection előtt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
            stopSelf()
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
            stopSelf()
            return
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "A rendszer leállította a képernyőrögzítést")
                mainHandler.post { stopSelf() }
            }
        }, mainHandler)
        projection = mp

        try {
            server = NetServer(serverListener).also { it.start() }
            discovery = DiscoveryResponder().also { it.start() }
            setupCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Indítási hiba: ${e.message}", e)
            stopSelf()
            return
        }
        isRunning = true
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
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isRunning) return
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

    private val encoderListener = object : ScreenEncoder.Listener {
        override fun onCodecConfig(data: ByteArray) {
            server?.setCodecConfig(data)
        }

        override fun onFrame(data: ByteArray, keyframe: Boolean, ptsUs: Long) {
            server?.sendFrame(data, keyframe, ptsUs)
        }
    }

    private val serverListener = object : NetServer.Listener {
        override fun onClientConnected(address: String) {
            clientAddress = address
            encoder?.requestKeyframe()
        }

        override fun onClientDisconnected() {
            clientAddress = null
        }

        override fun onTouch(action: Int, x: Float, y: Float) {
            TouchInjectorService.instance?.injectTouch(action, x, y)
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
        videoInfo = ""
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        encoder?.stop()
        encoder = null
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
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

    private fun buildNotification(): Notification {
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
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
