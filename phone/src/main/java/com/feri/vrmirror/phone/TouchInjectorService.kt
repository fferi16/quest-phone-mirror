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
        MirrorService.instance?.let {
            it.pushStatus()
            it.refreshKeepAwake()
        }
    }

    override fun onDestroy() {
        instance = null
        setKeepScreenOn(false)
        MirrorService.instance?.pushStatus()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- Képernyő ébren tartása ----
    //
    // Egy láthatatlan, 1x1 képpontos kisegítő-overlay ablak FLAG_KEEP_SCREEN_ON jelzővel.
    // Ezt a rendszer minden esetben tiszteletben tartja, szemben az elavult wake lockkal.

    private var keepOnView: android.view.View? = null

    fun setKeepScreenOn(on: Boolean) {
        handler.post {
            val wm = getSystemService(android.view.WindowManager::class.java) ?: return@post
            try {
                if (on && keepOnView == null) {
                    val v = android.view.View(this)
                    val lp = android.view.WindowManager.LayoutParams(
                        1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        android.graphics.PixelFormat.TRANSLUCENT
                    )
                    lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    wm.addView(v, lp)
                    keepOnView = v
                    Log.i(TAG, "Képernyő ébren tartása (overlay) bekapcsolva")
                } else if (!on && keepOnView != null) {
                    wm.removeView(keepOnView)
                    keepOnView = null
                    Log.i(TAG, "Képernyő ébren tartása (overlay) kikapcsolva")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ébren tartó overlay hiba: ${e.message}")
            }
        }
    }

    // ---- Publikus API (bármely szálról hívható) ----

    fun injectTouch(action: Int, nx: Float, ny: Float) {
        handler.post { handleTouch(action, nx, ny) }
    }

    /** Görgetés a (nx,ny) pontból: dx,dy "fok". */
    fun injectScroll(nx: Float, ny: Float, dx: Float, dy: Float) {
        handler.post { handleScroll(nx, ny, dx, dy) }
    }

    /** Kétujjas csippentés: közép (cx,cy) arányosan, spread = ujjtávolság a képernyő magasságának arányában. */
    fun injectPinch(action: Int, cx: Float, cy: Float, spread: Float) {
        handler.post { handlePinch(action, cx, cy, spread) }
    }

    // ---- Csippentés (két virtuális ujj) ----

    private var pinchA: GestureDescription.StrokeDescription? = null
    private var pinchB: GestureDescription.StrokeDescription? = null
    private var pinchLastA = PointF()
    private var pinchLastB = PointF()
    private var pinchLastTime = 0L
    private var pinchBusy = false
    private var pinchToken = 0
    private var pinchPendingAction = -1
    private var pinchPendingA = PointF()
    private var pinchPendingB = PointF()

    /** A két ujj helye a középpont és a távolság alapján (45 fokos átló mentén). */
    private fun pinchPoints(cx: Float, cy: Float, spread: Float): Pair<PointF, PointF> {
        val size = screenSize()
        val c = screenPoint(cx, cy)
        val half = (spread.coerceIn(0.02f, 1.5f) * size.y / 2f)
        val off = half * 0.7071f
        val a = PointF((c.x - off).coerceIn(1f, size.x - 2f), (c.y - off).coerceIn(1f, size.y - 2f))
        val b = PointF((c.x + off).coerceIn(1f, size.x - 2f), (c.y + off).coerceIn(1f, size.y - 2f))
        return a to b
    }

    private fun handlePinch(action: Int, cx: Float, cy: Float, spread: Float) {
        val (a, b) = pinchPoints(cx, cy, spread)
        when (action) {
            Protocol.PINCH_START -> {
                // Minden más gesztust eldobunk.
                handler.removeCallbacks(scrollEnd)
                scrollActive = false
                stroke = null
                busy = false
                busyToken++
                pendingAction = -1
                pinchA = null
                pinchB = null
                pinchBusy = false
                pinchToken++
                pinchPendingAction = -1
                Log.d(TAG, "Csippentés kezdete: A=(${a.x},${a.y}) B=(${b.x},${b.y})")
                val sa = GestureDescription.StrokeDescription(Path().apply { moveTo(a.x, a.y); lineTo(a.x, a.y) }, 0, 1, true)
                val sb = GestureDescription.StrokeDescription(Path().apply { moveTo(b.x, b.y); lineTo(b.x, b.y) }, 0, 1, true)
                dispatchPinch(sa, sb, a, b, willContinue = true)
            }
            Protocol.PINCH_UPDATE, Protocol.PINCH_END -> {
                if (pinchA == null || pinchB == null) return
                if (pinchBusy) {
                    // Egy UPDATE után jövő END nem veszhet el.
                    if (pinchPendingAction != Protocol.PINCH_END) pinchPendingAction = action
                    pinchPendingA = a
                    pinchPendingB = b
                    return
                }
                continuePinch(a, b, willContinue = action == Protocol.PINCH_UPDATE)
            }
        }
    }

    private fun continuePinch(a: PointF, b: PointF, willContinue: Boolean) {
        val ca = pinchA ?: return
        val cb = pinchB ?: return
        val now = SystemClock.uptimeMillis()
        val duration = (now - pinchLastTime).coerceIn(1L, MAX_SEGMENT_MS)
        val na = try {
            ca.continueStroke(Path().apply { moveTo(pinchLastA.x, pinchLastA.y); lineTo(a.x, a.y) }, 0, duration, willContinue)
        } catch (e: Exception) {
            Log.w(TAG, "continueStroke (A) hiba: ${e.message}"); pinchA = null; pinchB = null; return
        }
        val nb = try {
            cb.continueStroke(Path().apply { moveTo(pinchLastB.x, pinchLastB.y); lineTo(b.x, b.y) }, 0, duration, willContinue)
        } catch (e: Exception) {
            Log.w(TAG, "continueStroke (B) hiba: ${e.message}"); pinchA = null; pinchB = null; return
        }
        if (!willContinue) Log.d(TAG, "Csippentés vége")
        dispatchPinch(na, nb, a, b, willContinue)
    }

    private fun dispatchPinch(
        sa: GestureDescription.StrokeDescription,
        sb: GestureDescription.StrokeDescription,
        a: PointF, b: PointF, willContinue: Boolean
    ) {
        pinchA = if (willContinue) sa else null
        pinchB = if (willContinue) sb else null
        pinchLastA = a
        pinchLastB = b
        pinchLastTime = SystemClock.uptimeMillis()
        pinchBusy = true
        val token = ++pinchToken

        val gesture = GestureDescription.Builder().addStroke(sa).addStroke(sb).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (token != pinchToken) return
                pinchBusy = false
                flushPinchPending()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (token != pinchToken) return
                Log.w(TAG, "Csippentés megszakítva (willContinue=$willContinue)")
                pinchBusy = false
                pinchA = null
                pinchB = null
                pinchPendingAction = -1
            }
        }, handler)
        if (!ok) {
            Log.w(TAG, "dispatchGesture (csippentés) visszautasítva")
            pinchBusy = false
            pinchA = null
            pinchB = null
            pinchPendingAction = -1
            return
        }
        handler.postDelayed({
            if (token == pinchToken && pinchBusy) {
                pinchBusy = false
                flushPinchPending()
            }
        }, sa.duration + 100)
    }

    private fun flushPinchPending() {
        val action = pinchPendingAction
        pinchPendingAction = -1
        if (pinchA == null || pinchB == null || action < 0) return
        continuePinch(pinchPendingA, pinchPendingB, willContinue = action == Protocol.PINCH_UPDATE)
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
        if (action == Protocol.TOUCH_DOWN && pinchA != null) {
            // Csippentés közben érkező új érintés: a csippentést eldobjuk.
            pinchA = null
            pinchB = null
            pinchToken++
            pinchBusy = false
            pinchPendingAction = -1
        }
        handleTouchPx(action, screenPoint(nx, ny))
    }

    // Koppintás-holtsáv: a Quest mutatója remeg, ezért a lenyomás helyétől
    // csak akkor mozdulunk el, ha a mozgás meghaladja a küszöböt. Így a koppintás
    // tiszta koppintás marad, nem apró húzás.
    private var downPoint = PointF()
    private var withinSlop = false

    private fun handleTouchPx(action: Int, rawPoint: PointF) {
        var p = rawPoint
        when (action) {
            Protocol.TOUCH_DOWN -> {
                downPoint = PointF(p.x, p.y)
                withinSlop = true
            }
            Protocol.TOUCH_MOVE -> {
                if (withinSlop) {
                    val slop = screenSize().y * 0.015f
                    val dx = p.x - downPoint.x
                    val dy = p.y - downPoint.y
                    if (dx * dx + dy * dy < slop * slop) return
                    withinSlop = false
                }
            }
            Protocol.TOUCH_UP, Protocol.TOUCH_CANCEL -> {
                if (withinSlop) p = downPoint
                withinSlop = false
            }
        }
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
