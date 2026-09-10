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

    /** Görgetés a (nx,ny) pontból: dx,dy "fok" (egy fok = a képernyő ~15%-a). */
    fun injectScroll(nx: Float, ny: Float, dx: Float, dy: Float) {
        handler.post { handleScroll(nx, ny, dx, dy) }
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

    // ---- Görgetés (hüvelykujj-kar / egérgörgő) ----

    private var scrollBusy = false
    private var scrollPendingDx = 0f
    private var scrollPendingDy = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    private fun handleScroll(nx: Float, ny: Float, dx: Float, dy: Float) {
        // Húzás közben nem görgetünk külön.
        if (stroke != null) return
        val p = screenPoint(nx, ny)
        scrollX = p.x
        scrollY = p.y
        scrollPendingDx += dx
        scrollPendingDy += dy
        if (!scrollBusy) dispatchScroll()
    }

    private fun dispatchScroll() {
        val dx = scrollPendingDx
        val dy = scrollPendingDy
        scrollPendingDx = 0f
        scrollPendingDy = 0f
        if (dx == 0f && dy == 0f) return

        val metrics = DisplayMetrics()
        getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.let { @Suppress("DEPRECATION") it.getRealMetrics(metrics) }
        val w = metrics.widthPixels.toFloat().coerceAtLeast(1f)
        val h = metrics.heightPixels.toFloat().coerceAtLeast(1f)
        val step = h * 0.15f

        // Görgő felfelé (dy > 0) = a tartalom lefelé mozog = az ujj lefelé húz.
        // Görgő jobbra (dx > 0) = a tartalom jobbra gördül = az ujj balra húz.
        val startX = scrollX.coerceIn(1f, w - 2f)
        val startY = scrollY.coerceIn(1f, h - 2f)
        val endX = (startX - dx * step).coerceIn(1f, w - 2f)
        val endY = (startY + dy * step).coerceIn(1f, h - 2f)
        if (endX == startX && endY == startY) return

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 160))
            .build()
        scrollBusy = true
        Log.d(TAG, "Görgetés: ($startX,$startY) -> ($endX,$endY)")
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                scrollBusy = false
                dispatchScroll()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                scrollBusy = false
                scrollPendingDx = 0f
                scrollPendingDy = 0f
            }
        }, handler)
        if (!ok) scrollBusy = false
    }

    // ---- Érintés / húzás ----

    private fun handleTouch(action: Int, nx: Float, ny: Float) {
        val p = screenPoint(nx, ny)
        if (action != Protocol.TOUCH_MOVE) {
            Log.d(TAG, "Érintés: action=$action (${p.x},${p.y}) busy=$busy stroke=${stroke != null}")
        }
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

    private var moveCount = 0

    private fun continueTo(p: PointF, willContinue: Boolean) {
        val current = stroke ?: return
        val now = SystemClock.uptimeMillis()
        val duration = (now - lastTime).coerceIn(1L, MAX_SEGMENT_MS)
        if (willContinue) moveCount++ else {
            Log.d(TAG, "Húzás vége: $moveCount mozgás-szakasz, utolsó szakasz ${duration}ms")
            moveCount = 0
        }
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
                Log.w(TAG, "Gesztus megszakítva (willContinue=$willContinue)")
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
