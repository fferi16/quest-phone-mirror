package com.feri.vrmirror.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.feri.vrmirror.common.Protocol

/**
 * Kisegítő lehetőségek szolgáltatás, amely a Questről érkező érintéseket
 * valódi gesztusként lejátssza a telefon képernyőjén.
 *
 * Az Android csak "előre megírt" gesztusokat enged lejátszani, ezért a folyamatos
 * húzást a [GestureDescription.StrokeDescription.continueStroke] mechanizmussal
 * darabokból rakjuk össze: lenyomás -> (mozgás)* -> felengedés.
 */
class TouchInjectorService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchInjector"

        @Volatile
        var instance: TouchInjectorService? = null
            private set

        /** Ennyi ms-nál hosszabb szakaszt nem játszunk le egyben (különben nő a késés). */
        private const val MAX_SEGMENT_MS = 120L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var busy = false
    private var busyToken = 0

    private var pendingAction = -1
    private var pendingX = 0f
    private var pendingY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Érintésvezérlés bekapcsolva")
        MirrorService.instance?.pushStatus()
    }

    override fun onDestroy() {
        instance = null
        MirrorService.instance?.pushStatus()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- Publikus API (bármely szálról hívható) ----

    fun injectTouch(action: Int, nx: Float, ny: Float) {
        handler.post { handleTouch(action, nx, ny) }
    }

    fun pressKey(key: Int) {
        handler.post {
            val action = when (key) {
                Protocol.KEY_BACK -> GLOBAL_ACTION_BACK
                Protocol.KEY_HOME -> GLOBAL_ACTION_HOME
                Protocol.KEY_RECENTS -> GLOBAL_ACTION_RECENTS
                else -> return@post
            }
            performGlobalAction(action)
        }
    }

    // ---- Belső logika (fő szálon) ----

    private fun screenPoint(nx: Float, ny: Float): PointF {
        val metrics = DisplayMetrics()
        val display: Display? = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics) ?: resources.displayMetrics.let { metrics.setTo(it) }
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        return PointF(
            (nx * w).coerceIn(0f, w - 1f),
            (ny * h).coerceIn(0f, h - 1f)
        )
    }

    private fun handleTouch(action: Int, nx: Float, ny: Float) {
        val p = screenPoint(nx, ny)
        when (action) {
            Protocol.TOUCH_DOWN -> {
                // Ha valami félbemaradt, eldobjuk – új gesztus kezdődik.
                stroke = null
                busy = false
                busyToken++
                pendingAction = -1
                val path = Path().apply {
                    moveTo(p.x, p.y)
                    lineTo(p.x, p.y)
                }
                dispatch(GestureDescription.StrokeDescription(path, 0, 1, true), p, willContinue = true)
            }
            Protocol.TOUCH_MOVE -> {
                if (stroke == null) return
                if (busy) {
                    pendingAction = Protocol.TOUCH_MOVE
                    pendingX = p.x
                    pendingY = p.y
                    return
                }
                continueTo(p, willContinue = true)
            }
            Protocol.TOUCH_UP, Protocol.TOUCH_CANCEL -> {
                if (stroke == null) return
                if (busy) {
                    pendingAction = Protocol.TOUCH_UP
                    pendingX = p.x
                    pendingY = p.y
                    return
                }
                continueTo(p, willContinue = false)
            }
        }
    }

    private fun continueTo(p: PointF, willContinue: Boolean) {
        val current = stroke ?: return
        val now = SystemClock.uptimeMillis()
        val duration = (now - lastTime).coerceIn(1L, MAX_SEGMENT_MS)
        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(p.x, p.y)
        }
        val next = try {
            current.continueStroke(path, 0, duration, willContinue)
        } catch (e: Exception) {
            Log.w(TAG, "continueStroke hiba: ${e.message}")
            stroke = null
            return
        }
        dispatch(next, p, willContinue)
    }

    private fun dispatch(s: GestureDescription.StrokeDescription, p: PointF, willContinue: Boolean) {
        stroke = if (willContinue) s else null
        lastX = p.x
        lastY = p.y
        lastTime = SystemClock.uptimeMillis()
        busy = true
        val token = ++busyToken

        val gesture = GestureDescription.Builder().addStroke(s).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (token != busyToken) return
                busy = false
                flushPending()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (token != busyToken) return
                busy = false
                stroke = null
                pendingAction = -1
            }
        }, handler)

        if (!ok) {
            Log.w(TAG, "dispatchGesture visszautasítva")
            busy = false
            stroke = null
            pendingAction = -1
            return
        }

        // Biztonsági háló: ha valamiért nem jön visszahívás, ne ragadjunk be.
        handler.postDelayed({
            if (token == busyToken && busy) {
                busy = false
                flushPending()
            }
        }, s.duration + 100)
    }

    private fun flushPending() {
        val action = pendingAction
        pendingAction = -1
        if (stroke == null || action < 0) return
        val p = PointF(pendingX, pendingY)
        when (action) {
            Protocol.TOUCH_MOVE -> continueTo(p, willContinue = true)
            Protocol.TOUCH_UP -> continueTo(p, willContinue = false)
        }
    }
}
