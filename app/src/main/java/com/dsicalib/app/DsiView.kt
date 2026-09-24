package com.dsicalib.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A single DS-style screen, drawn at a low native resolution (about 256 px wide) and
 * scaled up with nearest-neighbour filtering to fill the phone. Runs the System
 * Settings > Touch Screen calibration flow and shows accuracy stats afterwards.
 *
 * Shaking the device toggles a motion pointer: the gyroscope aims a hand cursor and
 * tapping anywhere acts as a touch at the cursor.
 */
class DsiView(context: Context) : View(context), SensorEventListener {

    private enum class State { MENU, TARGET, FAILED, TEST, RESULTS, RECORDS }

    private class Button(val label: String, val rect: RectF, val onClick: () -> Unit)

    // Logical screen size, and the integer factor it is scaled up by.
    private var lw = 256
    private var lh = 448
    private var scale = 1
    private val dst = RectF()
    private var screen: Bitmap? = null
    private var sc = Canvas()
    private var mmPerPx = 0.25f

    private val blitPaint = Paint().apply { isFilterBitmap = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f
        color = TEXT
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val bold = Paint(text).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val heading = Paint(bold).apply { textSize = 12f }
    private val headerText = Paint(heading).apply { textAlign = Paint.Align.CENTER }
    private val accentText = Paint(bold).apply { color = ACCENT_DARK }
    private val valueText = Paint(bold).apply { textAlign = Paint.Align.RIGHT }
    private val small = Paint(text).apply {
        textSize = 9f
        color = TEXT_LIGHT
    }
    private val smallCenter = Paint(small).apply { textAlign = Paint.Align.CENTER }
    private val big = Paint(bold).apply { textSize = 24f }
    private val gradeText = Paint(bold).apply {
        textSize = 28f
        color = WHITE
        textAlign = Paint.Align.CENTER
    }

    private val prefs = context.getSharedPreferences("calibration", Context.MODE_PRIVATE)
    private var calibration = Calibration.decode(prefs.getString(KEY_CALIBRATION, null)) ?: Calibration.IDENTITY
    private var pending: Calibration? = null
    private var pendingStats: CalibrationStats? = null
    private var results: CalibrationStats? = null
    private var newBest = false

    private val targetNames: Array<String> = resources.getStringArray(R.array.target_names)
    private val directions: Array<String> = resources.getStringArray(R.array.directions)

    private var state = State.MENU
    private var stateSince = 0L

    // Layout of the current state, rebuilt by layout().
    private val panel = RectF()
    private var panelTitle: String? = null
    private var panelLines: List<String> = emptyList()
    private var panelNote: String? = null
    private val area = RectF()
    private var buttons: List<Button> = emptyList()
    private var pressed: Button? = null
    private var pressedInside = false

    // Calibration run.
    private var targets: List<Pt> = emptyList()
    private val runArea = RectF()
    private val taken = mutableListOf<TouchSample>()
    private var targetShownAt = 0L
    private var targetHeld = false
    private var downAt = 0L
    private var downPos = Pt(0f, 0f)
    private var heldPos = Pt(0f, 0f)
    private var drift = 0f
    private var lastHit: Pt? = null
    private var lastHitAt = 0L
    private var testMark: Pt? = null

    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 40)
    } catch (e: RuntimeException) {
        null
    }

    // Motion pointer.
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val shake = ShakeDetector()
    private val pointer = GyroPointer()
    private var pointerOn = false
    private var fingerDown = false
    private var lastGyroNs = 0L
    private var runWithPointer = false
    private var resultsWithPointer = false
    private var banner: String? = null
    private var bannerUntil = 0L

    init {
        enter(State.MENU)
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun str(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    private fun mm(px: Float) = String.format(Locale.US, "%.1f mm", px * mmPerPx)

    // ----------------------------------------------------------------- flow

    private fun enter(s: State) {
        state = s
        stateSince = now()
        pressed = null
        testMark = null
        targetHeld = false
        if (s == State.TARGET) {
            runWithPointer = pointerOn
            taken.clear()
            lastHit = null
            targetShownAt = stateSince
        }
        layout()
        invalidate()
    }

    /** Handles the system back action. Returns false when the app should close. */
    fun onBack(): Boolean {
        if (state == State.MENU) return false
        enter(State.MENU)
        return true
    }

    private fun finishCalibration() {
        val maxErrorPx = MAX_ERROR_MM / mmPerPx
        val closeEnough = taken.all { dist(it.touch, it.target) <= maxErrorPx }
        val cal = if (closeEnough) Calibration.fit(taken.map { it.touch }, taken.map { it.target }) else null
        if (cal == null) {
            beep(ToneGenerator.TONE_PROP_NACK)
            enter(State.FAILED)
        } else {
            pending = cal
            pendingStats = CalibrationStats(taken.toList(), cal, mmPerPx)
            enter(State.TEST)
        }
    }

    private fun confirmCalibration() {
        val cal = pending ?: return
        val stats = pendingStats ?: return
        // A pointer run measures aim, not the touch screen, so it is scored but not applied.
        if (!runWithPointer) {
            calibration = cal
            prefs.edit().putString(KEY_CALIBRATION, cal.encode()).apply()
        }
        resultsWithPointer = runWithPointer
        results = stats
        newBest = recordResult(stats)
        enter(State.RESULTS)
    }

    // -------------------------------------------------------------- records

    private fun loadHistory(): List<Int> =
        prefs.getString(KEY_HISTORY, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    /** Saves [stats] to the records. Returns true if it is a new best score. */
    private fun recordResult(stats: CalibrationStats): Boolean {
        val history = (loadHistory() + stats.score).takeLast(HISTORY_SIZE)
        val best = prefs.getInt(KEY_BEST, -1)
        val bestError = prefs.getFloat(KEY_BEST_ERROR, Float.MAX_VALUE)
        prefs.edit()
            .putString(KEY_HISTORY, history.joinToString(","))
            .putInt(KEY_COUNT, prefs.getInt(KEY_COUNT, 0) + 1)
            .putInt(KEY_BEST, max(best, stats.score))
            .putFloat(KEY_BEST_ERROR, min(bestError, stats.avgErrorMm))
            .apply()
        return stats.score > best
    }

    private fun lastResultText(): String? {
        val last = loadHistory().lastOrNull() ?: return null
        return str(R.string.last_result, last, CalibrationStats.gradeFor(last))
    }

    // --------------------------------------------------------------- layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        // Integer scaling keeps every pixel the same size.
        scale = max(1, (w / NATIVE_WIDTH).roundToInt())
        lw = w / scale
        lh = h / scale
        val left = (w - lw * scale) / 2f
        val top = (h - lh * scale) / 2f
        dst.set(left, top, left + lw * scale, top + lh * scale)
        screen?.recycle()
        screen = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888).also { sc = Canvas(it) }
        val dm = resources.displayMetrics
        mmPerPx = 25.4f / ((dm.xdpi + dm.ydpi) / 2f) * scale
        if (state == State.TARGET) enter(State.TARGET) else layout()
    }

    private fun leftButton() = RectF(6f, lh - FOOTER_H + 5f, 6f + BTN_W, lh - 5f)

    private fun rightButton() = RectF(lw - 6f - BTN_W, lh - FOOTER_H + 5f, lw - 6f, lh - 5f)

    private fun layout() {
        val body = when (state) {
            State.MENU -> str(R.string.menu_text)
            State.TARGET -> str(if (pointerOn) R.string.target_text_pointer else R.string.target_text)
            State.FAILED -> str(R.string.failed_text)
            State.TEST -> str(R.string.test_text)
            State.RESULTS, State.RECORDS -> null
        }
        panelTitle = if (state == State.MENU) str(R.string.touch_screen) else null
        panelNote = if (state == State.MENU) lastResultText() else null
        val footerTop = lh - FOOTER_H

        if (body == null) {
            panel.setEmpty()
            panelLines = emptyList()
            area.set(0f, HEADER_H, lw.toFloat(), footerTop)
        } else {
            panelLines = wrap(body, text, lw - 2 * MARGIN - 2 * PAD)
            var h = 2 * PAD + panelLines.size * text.fontSpacing
            if (panelTitle != null) h += TITLE_H
            if (panelNote != null) h += text.fontSpacing + 4f
            if (state == State.TARGET) h += PIPS_H
            val top = HEADER_H + MARGIN
            panel.set(MARGIN, top, lw - MARGIN, top + h)
            // The calibration marks get the whole screen below the instructions.
            area.set(0f, panel.bottom + MARGIN, lw.toFloat(), if (state == State.TARGET) lh.toFloat() else footerTop)
        }

        buttons = when (state) {
            State.MENU -> {
                val bh = 34f
                val gap = 12f
                val top = area.centerY() - bh - gap / 2f
                listOf(
                    Button(str(R.string.touch_screen), RectF(28f, top, lw - 28f, top + bh)) { enter(State.TARGET) },
                    Button(str(R.string.records), RectF(28f, top + bh + gap, lw - 28f, top + 2 * bh + gap)) {
                        enter(State.RECORDS)
                    },
                    Button(str(R.string.back), leftButton()) { (context as? Activity)?.finish() },
                )
            }
            State.TARGET -> emptyList()
            State.FAILED -> listOf(
                Button(str(R.string.back), leftButton()) { enter(State.MENU) },
                Button(str(R.string.retry), rightButton()) { enter(State.TARGET) },
            )
            State.TEST -> listOf(
                Button(str(R.string.retry), leftButton()) { enter(State.TARGET) },
                Button(str(R.string.ok), rightButton()) { confirmCalibration() },
            )
            State.RESULTS -> listOf(
                Button(str(R.string.retry), leftButton()) { enter(State.TARGET) },
                Button(str(R.string.ok), rightButton()) { enter(State.MENU) },
            )
            State.RECORDS -> listOf(Button(str(R.string.back), leftButton()) { enter(State.MENU) })
        }

        if (state == State.TARGET) {
            val a = RectF(
                area.left + TARGET_INSET, area.top + TARGET_INSET,
                area.right - TARGET_INSET, area.bottom - TARGET_INSET,
            )
            // Same order as R.array.target_names.
            targets = listOf(
                Pt(a.left, a.top), Pt(a.right, a.bottom), Pt(a.right, a.top),
                Pt(a.left, a.bottom), Pt(a.centerX(), a.centerY()),
            )
            runArea.set(area)
        }
    }

    // ---------------------------------------------------------------- input

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (pointerOn) {
            // The finger is the "A button"; where it lands does not matter, the cursor aims.
            when (action) {
                MotionEvent.ACTION_DOWN -> fingerDown = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerDown = false
            }
            if (action != MotionEvent.ACTION_MOVE) handlePointer(action, pointer.pos, calibrated = false)
        } else {
            handlePointer(action, Pt((e.x - dst.left) / scale, (e.y - dst.top) / scale), calibrated = true)
        }
        invalidate()
        return true
    }

    /** Routes a press/move/release at [raw] screen coordinates to the current state. */
    private fun handlePointer(action: Int, raw: Pt, calibrated: Boolean) {
        if (state == State.TARGET) {
            handleTarget(action, raw)
        } else {
            val cal = if (state == State.TEST) pending ?: calibration else calibration
            val p = if (calibrated) cal.map(raw) else raw
            handleButtons(action, p)
            if (state == State.TEST && pressed == null && area.contains(p.x, p.y) &&
                (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
            ) {
                testMark = p
            }
        }
    }

    // ------------------------------------------------------- motion pointer

    fun startSensors() {
        val sm = sensors ?: return
        accelerometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stopSensors() {
        sensors?.unregisterListener(this)
        lastGyroNs = 0L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> if (shake.onReading(v[0], v[1], v[2], now())) togglePointer()
            Sensor.TYPE_GYROSCOPE -> if (pointerOn) {
                val dt = (event.timestamp - lastGyroNs) / 1e9f
                if (lastGyroNs != 0L && dt > 0f && dt < 0.1f) {
                    pointer.update(v[0], v[1], dt, lw / AIM_RANGE_RAD, lw, lh)
                    // Holding the "button" while aiming drags, just like a held touch.
                    if (fingerDown) handlePointer(MotionEvent.ACTION_MOVE, pointer.pos, calibrated = false)
                    invalidate()
                }
                lastGyroNs = event.timestamp
            }
        }
    }

    private fun togglePointer() {
        if (gyroscope == null) {
            showBanner(str(R.string.no_gyro))
            return
        }
        if (fingerDown) handlePointer(MotionEvent.ACTION_CANCEL, pointer.pos, calibrated = false)
        fingerDown = false
        pointerOn = !pointerOn
        pointer.center(lw, lh)
        lastGyroNs = 0L
        beep(if (pointerOn) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showBanner(str(if (pointerOn) R.string.pointer_on else R.string.pointer_off))
        // A run mixes neither input: restart it with the new one.
        if (state == State.TARGET) enter(State.TARGET) else invalidate()
    }

    private fun showBanner(message: String) {
        banner = message
        bannerUntil = now() + BANNER_MS
        invalidate()
    }

    private fun handleTarget(action: Int, raw: Pt) {
        val t = now()
        when (action) {
            MotionEvent.ACTION_DOWN -> if (area.contains(raw.x, raw.y)) {
                targetHeld = true
                downAt = t
                downPos = raw
                heldPos = raw
                drift = 0f
            }
            // While held, the sample follows the touch and the wander is measured.
            MotionEvent.ACTION_MOVE -> if (targetHeld) {
                heldPos = raw
                drift = max(drift, dist(raw, downPos))
            }
            MotionEvent.ACTION_UP -> if (targetHeld) {
                targetHeld = false
                val target = targets[taken.size]
                taken += TouchSample(target, heldPos, downAt - targetShownAt, drift)
                beep(ToneGenerator.TONE_PROP_BEEP)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                lastHit = target
                lastHitAt = t
                targetShownAt = t
                if (taken.size == targets.size) finishCalibration()
            }
            MotionEvent.ACTION_CANCEL -> targetHeld = false
        }
    }

    private fun handleButtons(action: Int, p: Pt) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                pressed = buttons.firstOrNull { it.rect.contains(p.x, p.y) }
                pressedInside = pressed != null
            }
            MotionEvent.ACTION_MOVE -> pressedInside = pressed?.rect?.contains(p.x, p.y) == true
            MotionEvent.ACTION_UP -> {
                val b = pressed
                pressed = null
                if (b != null && b.rect.contains(p.x, p.y)) {
                    beep(ToneGenerator.TONE_PROP_ACK)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    b.onClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
    }

    private fun beep(type: Int) {
        tone?.startTone(type, 60)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSensors()
        tone?.release()
    }

    // -------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val bmp = screen ?: return
        drawScreen(sc)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bmp, null, dst, blitPaint)

        val revealing = (state == State.RESULTS || state == State.RECORDS) && now() - stateSince < REVEAL_MS
        if (state == State.TARGET || revealing || now() < bannerUntil) postInvalidateOnAnimation()
    }

    private fun reveal(): Float {
        val t = min(1f, (now() - stateSince) / REVEAL_MS.toFloat())
        return 1f - (1f - t) * (1f - t)
    }

    private fun drawScreen(c: Canvas) {
        drawGrid(c)
        if (state == State.TARGET || state == State.TEST || state == State.FAILED) {
            fill.color = WHITE
            c.drawRect(area, fill)
            fill.color = PANEL_BORDER
            c.drawRect(area.left, area.top, area.right, area.top + 1f, fill)
        }
        val title = when (state) {
            State.MENU -> R.string.system_settings
            State.RESULTS -> if (resultsWithPointer) R.string.results_pointer else R.string.results
            State.RECORDS -> R.string.records
            else -> R.string.touch_screen
        }
        drawHeader(c, str(title))
        if (!panel.isEmpty) drawInfoPanel(c)
        when (state) {
            State.TARGET -> drawTargets(c)
            State.TEST -> drawTest(c)
            State.RESULTS -> drawResults(c)
            State.RECORDS -> drawRecords(c)
            State.MENU, State.FAILED -> Unit
        }
        if (state != State.TARGET) drawFooter(c)
        buttons.forEach { drawButton(c, it) }
        if (pointerOn) drawPointerBadge(c)
        drawBanner(c)
        if (pointerOn) drawHand(c, pointer.x.roundToInt(), pointer.y.roundToInt(), fingerDown)
    }

    private fun drawPointerBadge(c: Canvas) {
        val label = str(R.string.pointer_badge)
        val w = small.measureText(label) + 8f
        val r = RectF(lw - 4f - w, 5f, lw - 4f, HEADER_H - 6f)
        fill.color = ACCENT
        c.drawRoundRect(r, 3f, 3f, fill)
        smallCenter.color = WHITE
        c.drawText(label, r.centerX(), r.centerY() - (small.ascent() + small.descent()) / 2f, smallCenter)
        smallCenter.color = TEXT_LIGHT
    }

    private fun drawBanner(c: Canvas) {
        val message = banner ?: return
        val left = bannerUntil - now()
        if (left <= 0) {
            banner = null
            return
        }
        val lines = wrap(message, bold, lw - 2 * MARGIN - 2 * PAD)
        val h = 2 * PAD + lines.size * bold.fontSpacing
        val bottom = (if (state == State.TARGET) lh.toFloat() else lh - FOOTER_H) - MARGIN
        val r = RectF(MARGIN, bottom - h, lw - MARGIN, bottom)
        val alpha = (min(1f, left / 300f) * 255).toInt()
        fill.color = BANNER
        fill.alpha = alpha * 230 / 255
        c.drawRoundRect(r, 8f, 8f, fill)
        fill.alpha = 255
        bold.color = WHITE
        bold.alpha = alpha
        var y = r.top + PAD
        for (line in lines) {
            c.drawText(line, r.left + PAD, y - bold.ascent(), bold)
            y += bold.fontSpacing
        }
        bold.color = TEXT
    }

    /** Original pixel-art pointing hand; the fingertip is the hotspot. */
    private fun drawHand(c: Canvas, hx: Int, hy: Int, pressed: Boolean) {
        val left = hx - HAND_HOTSPOT_X
        for ((row, pixels) in HAND.withIndex()) {
            for ((col, ch) in pixels.withIndex()) {
                val color = when (ch) {
                    '#' -> HAND_OUTLINE
                    'o' -> if (pressed) ACCENT_LIGHT else WHITE
                    else -> continue
                }
                // Drop shadow first, then the pixel.
                fill.color = SHADOW
                c.drawRect((left + col + 1).toFloat(), (hy + row + 1).toFloat(), (left + col + 2).toFloat(), (hy + row + 2).toFloat(), fill)
                fill.color = color
                c.drawRect((left + col).toFloat(), (hy + row).toFloat(), (left + col + 1).toFloat(), (hy + row + 1).toFloat(), fill)
            }
        }
    }

    private fun drawGrid(c: Canvas) {
        c.drawColor(BG)
        stroke.color = GRID
        stroke.strokeWidth = 1f
        var x = 0.5f
        while (x < lw) {
            c.drawLine(x, 0f, x, lh.toFloat(), stroke)
            x += 16f
        }
        var y = 0.5f
        while (y < lh) {
            c.drawLine(0f, y, lw.toFloat(), y, stroke)
            y += 16f
        }
    }

    private fun drawHeader(c: Canvas, title: String) {
        fill.shader = LinearGradient(0f, 0f, 0f, HEADER_H, WHITE, HEADER_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, lw.toFloat(), HEADER_H, fill)
        fill.shader = null
        fill.color = ACCENT
        c.drawRect(0f, HEADER_H - 2f, lw.toFloat(), HEADER_H, fill)
        c.drawText(title, lw / 2f, (HEADER_H - 2f) / 2f - (headerText.ascent() + headerText.descent()) / 2f, headerText)
    }

    private fun drawFooter(c: Canvas) {
        val top = lh - FOOTER_H
        fill.shader = LinearGradient(0f, top, 0f, lh.toFloat(), HEADER_BOTTOM, FOOTER_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRect(0f, top, lw.toFloat(), lh.toFloat(), fill)
        fill.shader = null
        fill.color = PANEL_BORDER
        c.drawRect(0f, top, lw.toFloat(), top + 1f, fill)
    }

    private fun drawPanel(c: Canvas, r: RectF) {
        fill.color = PANEL
        c.drawRoundRect(r, 8f, 8f, fill)
        stroke.color = PANEL_BORDER
        stroke.strokeWidth = 1f
        c.drawRoundRect(inset(r, 0.5f), 8f, 8f, stroke)
    }

    private fun drawInfoPanel(c: Canvas) {
        drawPanel(c, panel)
        val x = panel.left + PAD
        var y = panel.top + PAD
        panelTitle?.let {
            c.drawText(it, x, y - heading.ascent(), heading)
            y += TITLE_H
        }
        for (line in panelLines) {
            c.drawText(line, x, y - text.ascent(), text)
            y += text.fontSpacing
        }
        if (state == State.TARGET) drawPips(c, panel.centerX(), y + PIPS_H / 2f)
        panelNote?.let { c.drawText(it, x, y + 4f - accentText.ascent(), accentText) }
    }

    private fun pulse(): Float = 0.5f + 0.5f * sin(((now() - targetShownAt) % 800L) / 800f * 2f * PI.toFloat())

    /** Progress dots: one per mark. */
    private fun drawPips(c: Canvas, cx: Float, cy: Float) {
        val gap = 14f
        var x = cx - (targets.size - 1) * gap / 2f
        for (i in targets.indices) {
            when {
                i < taken.size -> {
                    fill.color = ACCENT
                    c.drawCircle(x, cy, 3.5f, fill)
                }
                i == taken.size -> {
                    stroke.color = RED
                    stroke.strokeWidth = 1.5f
                    c.drawCircle(x, cy, 3f + pulse(), stroke)
                }
                else -> {
                    stroke.color = PANEL_BORDER
                    stroke.strokeWidth = 1f
                    c.drawCircle(x, cy, 3.5f, stroke)
                }
            }
            x += gap
        }
    }

    private fun drawTargets(c: Canvas) {
        if (taken.size < targets.size) drawTarget(c, targets[taken.size])
        val hit = lastHit ?: return
        val t = (now() - lastHitAt) / HIT_MS.toFloat()
        if (t < 1f) {
            stroke.color = ACCENT
            stroke.alpha = ((1f - t) * 255).toInt()
            stroke.strokeWidth = 1.5f
            c.drawCircle(hit.x, hit.y, 6f + 14f * t, stroke)
            stroke.alpha = 255
        }
    }

    private fun drawTarget(c: Canvas, t: Pt) {
        val ring = if (targetHeld) 5f else 6f + 3f * pulse()
        stroke.strokeWidth = 1.5f
        stroke.color = if (targetHeld) ACCENT else RED
        c.drawCircle(t.x, t.y, ring, stroke)

        stroke.strokeWidth = 1f
        stroke.color = TEXT
        c.drawLine(t.x - 12f, t.y, t.x + 12f, t.y, stroke)
        c.drawLine(t.x, t.y - 12f, t.x, t.y + 12f, stroke)

        fill.color = if (targetHeld) ACCENT else RED
        c.drawCircle(t.x, t.y, 1.5f, fill)
    }

    private fun drawTest(c: Canvas) {
        stroke.color = TEST_GRID
        stroke.strokeWidth = 1f
        var x = 16.5f
        while (x < lw) {
            c.drawLine(x, area.top + 1f, x, area.bottom, stroke)
            x += 16f
        }
        var y = area.top + 16.5f
        while (y < area.bottom) {
            c.drawLine(0f, y, lw.toFloat(), y, stroke)
            y += 16f
        }
        val p = testMark ?: return
        stroke.strokeWidth = 1.5f
        stroke.color = ACCENT
        c.drawCircle(p.x, p.y, 5f, stroke)
        stroke.strokeWidth = 1f
        stroke.color = TEXT
        c.drawLine(p.x - 8f, p.y, p.x + 8f, p.y, stroke)
        c.drawLine(p.x, p.y - 8f, p.x, p.y + 8f, stroke)
        val readout = String.format(Locale.US, "X %03d   Y %03d", p.x.roundToInt(), (p.y - area.top).roundToInt())
        c.drawText(readout, area.left + 6f, area.bottom - 6f, small)
    }

    private fun drawResults(c: Canvas) {
        val s = results ?: return
        val ease = reveal()
        val footerTop = lh - FOOTER_H

        // Grade card.
        val card = RectF(MARGIN, HEADER_H + MARGIN, lw - MARGIN, HEADER_H + MARGIN + CARD_H)
        drawPanel(c, card)
        val cx = card.left + 36f
        val cy = card.centerY()
        val r = 24f * (0.6f + 0.4f * ease)
        fill.color = gradeColor(s.grade)
        c.drawCircle(cx, cy, r, fill)
        stroke.color = WHITE
        stroke.strokeWidth = 2f
        c.drawCircle(cx, cy, r - 3f, stroke)
        gradeText.textSize = 28f * (0.6f + 0.4f * ease)
        c.drawText(s.grade.toString(), cx, cy - (gradeText.ascent() + gradeText.descent()) / 2f, gradeText)

        val x0 = card.left + 72f
        c.drawText(str(R.string.precision), x0, card.top + 16f, small)
        val shown = (s.score * ease).roundToInt().toString()
        c.drawText(shown, x0, card.top + 42f, big)
        c.drawText(" / 100", x0 + big.measureText(shown), card.top + 42f, text)
        if (newBest) {
            c.drawText(str(R.string.new_record), x0, card.top + 56f, accentText)
        } else {
            c.drawText(str(R.string.best_score, prefs.getInt(KEY_BEST, s.score)), x0, card.top + 56f, small)
        }

        val tendency = hypot(s.bias.x, s.bias.y)
        val shift = s.calibration.shiftAt(Pt(runArea.centerX(), runArea.centerY()))
        val rows = listOf(
            str(R.string.stat_avg_error) to mm(s.avgErrorPx),
            str(R.string.stat_worst) to "${mm(s.errorsPx[s.worstIndex])} · ${targetNames[s.worstIndex]}",
            str(R.string.stat_speed) to String.format(Locale.US, "%d ms / mark", s.avgReactionMs),
            str(R.string.stat_steadiness) to mm(s.avgDriftPx),
            str(R.string.stat_consistency) to mm(s.residualPx),
            str(R.string.stat_tendency) to
                if (tendency * mmPerPx < 0.3f) {
                    str(R.string.centered)
                } else {
                    "${mm(tendency)} ${directions[CalibrationStats.directionIndex(s.bias)]}"
                },
            str(R.string.stat_correction) to
                String.format(Locale.US, "%+.1f, %+.1f mm", shift.x * mmPerPx, shift.y * mmPerPx),
            str(R.string.stat_scale) to
                String.format(Locale.US, "%.1f%% · %.1f°", s.calibration.scale * 100f, s.calibration.rotationDeg),
        )

        val rowsH = rows.size * ROW_H + 2 * PAD
        val mapTop = card.bottom + GAP
        val mapH = (footerTop - GAP - rowsH - GAP - mapTop).coerceIn(80f, 180f)
        val mapPanel = RectF(MARGIN, mapTop, lw - MARGIN, mapTop + mapH)
        drawTouchMap(c, mapPanel, s)
        drawRows(c, RectF(MARGIN, mapPanel.bottom + GAP, lw - MARGIN, mapPanel.bottom + GAP + rowsH), rows, ease)
    }

    /** Miniature of the screen: each mark, with a line out to where it was touched (magnified). */
    private fun drawTouchMap(c: Canvas, p: RectF, s: CalibrationStats) {
        drawPanel(c, p)
        val box = RectF(p.left + PAD, p.top + 18f, p.right - PAD, p.bottom - PAD)
        val k = min(box.width() / runArea.width(), box.height() / runArea.height())
        val mw = runArea.width() * k
        val mh = runArea.height() * k
        val map = RectF(box.centerX() - mw / 2f, box.centerY() - mh / 2f, box.centerX() + mw / 2f, box.centerY() + mh / 2f)
        fill.color = WHITE
        c.drawRect(map, fill)
        stroke.color = PANEL_BORDER
        stroke.strokeWidth = 1f
        c.drawRect(map, stroke)

        val mag = (MAP_VECTOR / max(s.avgErrorPx * k, 0.01f)).coerceIn(1f, 10f).roundToInt()
        c.drawText(str(R.string.touch_map, mag), p.left + PAD, p.top + 13f, small)

        val color = gradeColor(s.grade)
        c.save()
        c.clipRect(map)
        s.samples.forEachIndexed { i, sample ->
            val tx = map.left + (sample.target.x - runArea.left) * k
            val ty = map.top + (sample.target.y - runArea.top) * k
            stroke.color = TEXT_LIGHT
            stroke.strokeWidth = 1f
            c.drawLine(tx - 4f, ty, tx + 4f, ty, stroke)
            c.drawLine(tx, ty - 4f, tx, ty + 4f, stroke)
            val hx = tx + (sample.touch.x - sample.target.x) * k * mag
            val hy = ty + (sample.touch.y - sample.target.y) * k * mag
            stroke.color = color
            stroke.strokeWidth = 1.5f
            c.drawLine(tx, ty, hx, hy, stroke)
            fill.color = if (i == s.worstIndex) RED else color
            c.drawCircle(hx, hy, 2.5f, fill)
        }
        c.restore()
    }

    private fun drawRows(c: Canvas, p: RectF, rows: List<Pair<String, String>>, reveal: Float) {
        drawPanel(c, p)
        val shown = ceil(rows.size * reveal).toInt()
        var y = p.top + PAD
        rows.forEachIndexed { i, (label, value) ->
            if (i % 2 == 1) {
                fill.color = ROW_ALT
                c.drawRect(p.left + 2f, y, p.right - 2f, y + ROW_H, fill)
            }
            if (i < shown) {
                val base = y + ROW_H / 2f - (text.ascent() + text.descent()) / 2f
                c.drawText(label, p.left + PAD, base, text)
                // Shrink long values so they never run into the label.
                valueText.textSize = 11f
                val room = p.width() - 2 * PAD - text.measureText(label) - 6f
                while (valueText.measureText(value) > room && valueText.textSize > 8f) valueText.textSize -= 0.5f
                c.drawText(value, p.right - PAD, base, valueText)
            }
            y += ROW_H
        }
    }

    private fun drawRecords(c: Canvas) {
        val history = loadHistory()
        val top = HEADER_H + MARGIN
        if (history.isEmpty()) {
            val lines = wrap(str(R.string.no_records), text, lw - 2 * MARGIN - 2 * PAD)
            val p = RectF(MARGIN, top, lw - MARGIN, top + 2 * PAD + lines.size * text.fontSpacing)
            drawPanel(c, p)
            var y = p.top + PAD
            for (line in lines) {
                c.drawText(line, p.left + PAD, y - text.ascent(), text)
                y += text.fontSpacing
            }
            return
        }

        val best = prefs.getInt(KEY_BEST, 0)
        val last = history.last()
        val rows = listOf(
            str(R.string.rec_count) to prefs.getInt(KEY_COUNT, history.size).toString(),
            str(R.string.rec_best) to "$best (${CalibrationStats.gradeFor(best)})",
            str(R.string.rec_last) to "$last (${CalibrationStats.gradeFor(last)})",
            str(R.string.rec_best_error) to String.format(Locale.US, "%.1f mm", prefs.getFloat(KEY_BEST_ERROR, 0f)),
        )
        val rowsPanel = RectF(MARGIN, top, lw - MARGIN, top + rows.size * ROW_H + 2 * PAD)
        drawRows(c, rowsPanel, rows, 1f)

        val chartTop = rowsPanel.bottom + GAP
        val chart = RectF(MARGIN, chartTop, lw - MARGIN, min(lh - FOOTER_H - GAP, chartTop + 200f))
        drawChart(c, chart, history, reveal())
    }

    /** Bar chart of the most recent scores, oldest on the left. */
    private fun drawChart(c: Canvas, p: RectF, history: List<Int>, reveal: Float) {
        drawPanel(c, p)
        c.drawText(str(R.string.recent_scores), p.left + PAD, p.top + 13f, small)
        val plot = RectF(p.left + PAD, p.top + 30f, p.right - PAD, p.bottom - PAD)

        stroke.color = GRID
        stroke.strokeWidth = 1f
        for (level in 1..4) {
            val y = plot.bottom - plot.height() * level / 4f
            c.drawLine(plot.left, y, plot.right, y, stroke)
        }
        stroke.color = PANEL_BORDER
        c.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, stroke)

        val slot = plot.width() / HISTORY_SIZE
        history.forEachIndexed { i, score ->
            val bh = max(1f, plot.height() * score / 100f * reveal)
            val x = plot.left + i * slot + slot * 0.2f
            val bar = RectF(x, plot.bottom - bh, x + slot * 0.6f, plot.bottom)
            fill.color = gradeColor(CalibrationStats.gradeFor(score))
            c.drawRoundRect(bar, 2f, 2f, fill)
            c.drawText(score.toString(), bar.centerX(), bar.top - 3f, smallCenter)
        }
    }

    private fun drawButton(c: Canvas, b: Button) {
        val r = b.rect
        val down = b === pressed && pressedInside
        fill.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            if (down) ACCENT_LIGHT else WHITE,
            if (down) ACCENT else BUTTON_BOTTOM,
            Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(r, 7f, 7f, fill)
        fill.shader = null
        stroke.color = if (down) ACCENT_DARK else BUTTON_BORDER
        stroke.strokeWidth = 1f
        c.drawRoundRect(inset(r, 0.5f), 7f, 7f, stroke)

        text.textAlign = Paint.Align.CENTER
        text.color = if (down) WHITE else TEXT
        c.drawText(b.label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2f, text)
        text.textAlign = Paint.Align.LEFT
        text.color = TEXT
    }

    private fun gradeColor(grade: Char) = when (grade) {
        'S' -> GOLD
        'A' -> ACCENT
        'B' -> GREEN
        'C' -> ORANGE
        else -> RED
    }

    private fun wrap(s: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ').filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                    lines += line
                    line = word
                } else {
                    line = candidate
                }
            }
            lines += line
        }
        return lines
    }

    private fun inset(r: RectF, d: Float) = RectF(r.left + d, r.top + d, r.right - d, r.bottom - d)

    companion object {
        private const val NATIVE_WIDTH = 256f

        private const val HEADER_H = 22f
        private const val FOOTER_H = 32f
        private const val MARGIN = 8f
        private const val PAD = 8f
        private const val GAP = 6f
        private const val TITLE_H = 18f
        private const val PIPS_H = 16f
        private const val ROW_H = 15f
        private const val CARD_H = 64f
        private const val BTN_W = 80f
        private const val TARGET_INSET = 24f

        /** A touch further than this from its mark fails the calibration. */
        private const val MAX_ERROR_MM = 8f

        /** Length in map pixels an average error is magnified to on the touch map. */
        private const val MAP_VECTOR = 10f

        private const val HIT_MS = 250L
        private const val BANNER_MS = 2500L

        /** Rotation (radians) that sweeps the cursor across the full screen width. */
        private const val AIM_RANGE_RAD = 0.7f

        private const val HAND_HOTSPOT_X = 4
        private val HAND = listOf(
            "....##........",
            "...#oo#.......",
            "...#oo#.......",
            "...#oo#.......",
            "...#oo###.....",
            "...#oo#oo##...",
            ".###oo#oo#o##.",
            "#oo#oooooooo#.",
            "#ooooooooooo#.",
            ".#oooooooooo#.",
            ".#oooooooooo#.",
            "..#ooooooooo#.",
            "..#oooooooo#..",
            "...#ooooooo#..",
            "...#ooooooo#..",
            "...#########..",
        )
        private const val REVEAL_MS = 900L
        private const val HISTORY_SIZE = 10

        private const val KEY_CALIBRATION = "affine_v2"
        private const val KEY_HISTORY = "history"
        private const val KEY_COUNT = "count"
        private const val KEY_BEST = "best"
        private const val KEY_BEST_ERROR = "best_error_mm"

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BG = 0xFFF4F4F4.toInt()
        private const val GRID = 0xFFE4E4E4.toInt()
        private const val TEST_GRID = 0xFFEEF3F7.toInt()
        private const val PANEL = 0xFFFFFFFF.toInt()
        private const val PANEL_BORDER = 0xFFB8B8B8.toInt()
        private const val ROW_ALT = 0xFFF1F6FA.toInt()
        private const val HEADER_BOTTOM = 0xFFDADADA.toInt()
        private const val FOOTER_BOTTOM = 0xFFBDBDBD.toInt()
        private const val BUTTON_BOTTOM = 0xFFDCDCDC.toInt()
        private const val BUTTON_BORDER = 0xFF9A9A9A.toInt()
        private const val TEXT = 0xFF464646.toInt()
        private const val TEXT_LIGHT = 0xFF8A8A8A.toInt()
        private const val ACCENT = 0xFF2AA0E6.toInt()
        private const val ACCENT_LIGHT = 0xFF8FD3FA.toInt()
        private const val ACCENT_DARK = 0xFF1573AD.toInt()
        private const val RED = 0xFFE03C31.toInt()
        private const val GOLD = 0xFFE8B21E.toInt()
        private const val GREEN = 0xFF3DB86A.toInt()
        private const val ORANGE = 0xFFF08C28.toInt()
        private const val BANNER = 0xFF2B3A48.toInt()
        private const val HAND_OUTLINE = 0xFF1E3A5F.toInt()
        private const val SHADOW = 0x40000000
    }
}
