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
    //
    // A görgetést egy "virtuális ujj" folyamatos húzásaként játsszuk le, ugyanazzal a
    // mechanizmussal, mint a kézi húzást: lenyomás -> mozgás-szakaszok -> felengedés,
    // ha ~150 ms-ig nem jön újabb görgetés. Ha az ujj a képernyő széléhez ér,
    // felemeljük és a kiindulópontból folytatjuk.

    private var scrollActive = false
    private var scrollPos = PointF()
    private var scrollOrigin = PointF()
    private val scrollEnd = Runnable { endScroll() }

    private fun screenSize(): PointF {
        val metrics = DisplayMetrics()
        getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.let { @Suppress("DEPRECATION") it.getRealMetrics(metrics) }
        return PointF(
            metrics.widthPixels.toFloat().coerceAtLeast(2f),
            metrics.heightPixels.toFloat().coerceAtLeast(2f)
        )
    }

    private fun handleScroll(nx: Float, ny: Float, dx: Float, dy: Float) {
        // Kézi húzás közben nem görgetünk külön.
        if (!scrollActive && stroke != null) return
        val size = screenSize()
        // Egy görgő-"fok" ennyi képpont (a képernyő magasságának 4%-a).
        val step = size.y * 0.04f
        // Görgő felfelé (dy > 0) = a tartalom lefelé mozog = az ujj lefelé húz.
        // Görgő jobbra (dx > 0) = a tartalom jobbra gördül = az ujj balra húz.
        val moveX = -dx * step
        val moveY = dy * step

        if (!scrollActive) {
            val p = screenPoint(nx, ny)
            // A kiindulópont a képernyő közepe táján legyen, hogy legyen hely mindkét irányba.
            scrollOrigin = PointF(
                p.x.coerceIn(size.x * 0.15f, size.x * 0.85f),
                p.y.coerceIn(size.y * 0.3f, size.y * 0.7f)
            )
            scrollPos = PointF(scrollOrigin.x, scrollOrigin.y)
            scrollActive = true
            handleTouchPx(Protocol.TOUCH_DOWN, scrollPos)
        }

        var nextX = scrollPos.x + moveX
        var nextY = scrollPos.y + moveY
        val margin = size.y * 0.05f
        if (nextY < margin || nextY > size.y - margin || nextX < margin || nextX > size.x - margin) {
            // Elértük a szélét: felemeljük az ujjat, és a kiindulópontból folytatjuk.
            handleTouchPx(Protocol.TOUCH_UP, scrollPos)
            scrollPos = PointF(scrollOrigin.x, scrollOrigin.y)
            handleTouchPx(Protocol.TOUCH_DOWN, scrollPos)
            nextX = (scrollPos.x + moveX).coerceIn(margin, size.x - margin)
            nextY = (scrollPos.y + moveY).coerceIn(margin, size.y - margin)
        }
        scrollPos = PointF(nextX, nextY)
        handleTouchPx(Protocol.TOUCH_MOVE, scrollPos)

        handler.removeCallbacks(scrollEnd)
        handler.postDelayed(scrollEnd, 150)
    }

    private fun endScroll() {
        if (!scrollActive) return
        scrollActive = false
        handleTouchPx(Protocol.TOUCH_UP, scrollPos)
    }

    // ---- Érintés / húzás ----

    private fun handleTouch(action: Int, nx: Float, ny: Float) {
        if (scrollActive && action == Protocol.TOUCH_DOWN) {
            handler.removeCallbacks(scrollEnd)
            scrollActive = false
        }
        handleTouchPx(action, screenPoint(nx, ny))
    }

    private fun handleTouchPx(action: Int, p: PointF) {
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
