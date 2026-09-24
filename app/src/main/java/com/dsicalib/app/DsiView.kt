package com.dsicalib.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a DSi-style pair of 256x192 screens (top + touch screen) and runs the
 * System Settings > Touch Screen calibration flow on the bottom one.
 */
class DsiView(context: Context) : View(context) {

    private enum class State { MENU, TARGET, FAILED, TEST, DONE }

    private class Button(val label: String, val rect: RectF, val onClick: () -> Unit)

    private val topBmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val bottomBmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val topCanvas = Canvas(topBmp)
    private val bottomCanvas = Canvas(bottomBmp)

    // Nearest-neighbour upscaling keeps the native-resolution pixel look.
    private val blitPaint = Paint().apply { isFilterBitmap = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f
        color = TEXT
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val titleText = Paint(text).apply {
        textSize = 12f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val topRect = RectF()
    private val bottomRect = RectF()
    private var scale = 1f

    private val prefs = context.getSharedPreferences("calibration", Context.MODE_PRIVATE)
    private var calibration = Calibration.decode(prefs.getString(KEY_CALIBRATION, null)) ?: Calibration.IDENTITY
    private var pending: Calibration? = null

    private var state = State.MENU
    private var stateSince = 0L
    private var targetIndex = 0
    private val samples = arrayOfNulls<Pt>(TARGETS.size)
    private var targetHeld = false
    private var testMark: Pt? = null
    private var buttons: List<Button> = emptyList()
    private var pressed: Button? = null
    private var pressedInside = false

    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 40)
    } catch (e: RuntimeException) {
        null
    }

    private val returnToMenu = Runnable { enter(State.MENU) }

    init {
        enter(State.MENU)
    }

    private fun str(id: Int) = context.getString(id)

    private fun enter(s: State) {
        removeCallbacks(returnToMenu)
        state = s
        stateSince = SystemClock.uptimeMillis()
        pressed = null
        testMark = null
        targetHeld = false
        if (s == State.TARGET) {
            targetIndex = 0
            samples.fill(null)
        }
        buttons = when (s) {
            State.MENU -> listOf(
                Button(str(R.string.touch_screen), RectF(48f, 64f, 208f, 100f)) { enter(State.TARGET) },
                Button(str(R.string.back), BTN_LEFT) { (context as? Activity)?.finish() },
            )
            State.FAILED -> listOf(Button(str(R.string.ok), BTN_RIGHT) { enter(State.TARGET) })
            State.TEST -> listOf(
                Button(str(R.string.retry), BTN_LEFT) { enter(State.TARGET) },
                Button(str(R.string.ok), BTN_RIGHT) { confirmCalibration() },
            )
            State.TARGET, State.DONE -> emptyList()
        }
        if (s == State.DONE) postDelayed(returnToMenu, DONE_DELAY_MS)
        invalidate()
    }

    /** Handles the system back action. Returns false when the app should close. */
    fun onBack(): Boolean {
        if (state == State.MENU) return false
        enter(State.MENU)
        return true
    }

    private fun confirmCalibration() {
        val cal = pending ?: return
        calibration = cal
        prefs.edit().putString(KEY_CALIBRATION, cal.encode()).apply()
        enter(State.DONE)
    }

    private fun finishCalibration() {
        val raws = samples.map { it!! }
        val closeEnough = raws.zip(TARGETS).all { (r, t) -> hypot(r.x - t.x, r.y - t.y) <= MAX_ERROR }
        val cal = if (closeEnough) Calibration.solve(raws, TARGETS) else null
        if (cal == null) {
            beep(ToneGenerator.TONE_PROP_NACK)
            enter(State.FAILED)
        } else {
            pending = cal
            enter(State.TEST)
        }
    }

    // ---------------------------------------------------------------- layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val totalH = 2 * H + HINGE
        var s = min(w.toFloat() / W, h.toFloat() / totalH)
        if (s >= 2f) s = floor(s) // integer scale keeps every pixel the same size
        scale = s
        val left = (w - W * s) / 2f
        val top = (h - totalH * s) / 2f
        topRect.set(left, top, left + W * s, top + H * s)
        val bTop = top + (H + HINGE) * s
        bottomRect.set(left, bTop, left + W * s, bTop + H * s)
    }

    // ----------------------------------------------------------------- input

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val raw = Pt((e.x - bottomRect.left) / scale, (e.y - bottomRect.top) / scale)
        val inside = raw.x in 0f..W.toFloat() && raw.y in 0f..H.toFloat()
        val action = e.actionMasked

        if (state == State.TARGET) {
            handleTarget(action, raw, inside)
        } else {
            val cal = if (state == State.TEST) pending ?: calibration else calibration
            val p = cal.map(raw)
            handleButtons(action, p)
            if (state == State.TEST && pressed == null && inside &&
                (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
            ) {
                testMark = p
            }
        }
        invalidate()
        return true
    }

    private fun handleTarget(action: Int, raw: Pt, inside: Boolean) {
        when (action) {
            MotionEvent.ACTION_DOWN -> if (inside) {
                samples[targetIndex] = raw
                targetHeld = true
            }
            // While the stylus is held, the sample follows it (like pressing down harder / adjusting).
            MotionEvent.ACTION_MOVE -> if (targetHeld && inside) samples[targetIndex] = raw
            MotionEvent.ACTION_UP -> if (targetHeld) {
                targetHeld = false
                beep(ToneGenerator.TONE_PROP_BEEP)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                targetIndex++
                stateSince = SystemClock.uptimeMillis()
                if (targetIndex == TARGETS.size) finishCalibration()
            }
            MotionEvent.ACTION_CANCEL -> {
                targetHeld = false
                samples[targetIndex] = null
            }
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
        removeCallbacks(returnToMenu)
        tone?.release()
    }

    // --------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BODY)
        drawTop(topCanvas)
        drawBottom(bottomCanvas)

        fill.color = BEZEL
        val b = 3f * scale
        canvas.drawRect(topRect.left - b, topRect.top - b, topRect.right + b, topRect.bottom + b, fill)
        canvas.drawRect(bottomRect.left - b, bottomRect.top - b, bottomRect.right + b, bottomRect.bottom + b, fill)
        fill.color = HINGE_COLOR
        canvas.drawRect(0f, topRect.bottom + b * 2, width.toFloat(), bottomRect.top - b * 2, fill)

        canvas.drawBitmap(topBmp, null, topRect, blitPaint)
        canvas.drawBitmap(bottomBmp, null, bottomRect, blitPaint)

        if (state == State.TARGET) postInvalidateOnAnimation()
    }

    private fun drawTop(c: Canvas) {
        drawGridBackground(c)
        drawHeader(c, str(if (state == State.MENU) R.string.system_settings else R.string.touch_screen))

        val panel = RectF(12f, 32f, 244f, 180f)
        fill.color = PANEL
        c.drawRoundRect(panel, 8f, 8f, fill)
        stroke.color = PANEL_BORDER
        stroke.strokeWidth = 1f
        c.drawRoundRect(inset(panel, 0.5f), 8f, 8f, stroke)

        var y = panel.top + 18f
        if (state == State.MENU) {
            titleText.textAlign = Paint.Align.LEFT
            c.drawText(str(R.string.touch_screen), panel.left + 12f, y, titleText)
            titleText.textAlign = Paint.Align.CENTER
            y += 22f
        }
        val body = when (state) {
            State.MENU -> R.string.menu_text
            State.TARGET -> R.string.target_text
            State.FAILED -> R.string.failed_text
            State.TEST -> R.string.test_text
            State.DONE -> R.string.done_text
        }
        drawWrapped(c, str(body), panel.left + 12f, y, panel.width() - 24f)
    }

    private fun drawBottom(c: Canvas) {
        when (state) {
            State.MENU -> {
                drawGridBackground(c)
                drawHeader(c, str(R.string.system_settings))
                drawFooter(c)
            }
            State.TARGET -> {
                c.drawColor(WHITE)
                if (targetIndex < TARGETS.size) drawTarget(c, TARGETS[targetIndex])
            }
            State.FAILED, State.TEST -> {
                c.drawColor(WHITE)
                drawFooter(c)
                testMark?.let { drawMark(c, it) }
            }
            State.DONE -> c.drawColor(WHITE)
        }
        buttons.forEach { drawButton(c, it) }
    }

    private fun drawGridBackground(c: Canvas) {
        c.drawColor(BG)
        stroke.color = GRID
        stroke.strokeWidth = 1f
        var i = 0.5f
        while (i < W) {
            c.drawLine(i, 0f, i, H.toFloat(), stroke)
            i += 16f
        }
        i = 0.5f
        while (i < H) {
            c.drawLine(0f, i, W.toFloat(), i, stroke)
            i += 16f
        }
    }

    private fun drawHeader(c: Canvas, title: String) {
        fill.shader = LinearGradient(0f, 0f, 0f, 20f, WHITE, HEADER_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), 20f, fill)
        fill.shader = null
        fill.color = ACCENT
        c.drawRect(0f, 19f, W.toFloat(), 21f, fill)
        c.drawText(title, W / 2f, 14.5f, titleText)
    }

    private fun drawFooter(c: Canvas) {
        fill.shader = LinearGradient(0f, 160f, 0f, H.toFloat(), HEADER_BOTTOM, FOOTER_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRect(0f, 160f, W.toFloat(), H.toFloat(), fill)
        fill.shader = null
        fill.color = PANEL_BORDER
        c.drawRect(0f, 160f, W.toFloat(), 161f, fill)
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

    private fun drawTarget(c: Canvas, t: Pt) {
        val phase = ((SystemClock.uptimeMillis() - stateSince) % 800L) / 800f
        val ring = if (targetHeld) 5f else 7f + 2f * sin(phase * 2f * PI.toFloat())

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

    private fun drawMark(c: Canvas, p: Pt) {
        stroke.strokeWidth = 1.5f
        stroke.color = ACCENT
        c.drawCircle(p.x, p.y, 5f, stroke)
        stroke.strokeWidth = 1f
        stroke.color = TEXT
        c.drawLine(p.x - 8f, p.y, p.x + 8f, p.y, stroke)
        c.drawLine(p.x, p.y - 8f, p.x, p.y + 8f, stroke)
    }

    private fun drawWrapped(c: Canvas, s: String, x: Float, startY: Float, maxWidth: Float) {
        text.textAlign = Paint.Align.LEFT
        val lineHeight = text.fontSpacing
        var y = startY
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ').filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && text.measureText(candidate) > maxWidth) {
                    c.drawText(line, x, y, text)
                    y += lineHeight
                    line = word
                } else {
                    line = candidate
                }
            }
            if (line.isNotEmpty()) c.drawText(line, x, y, text)
            y += lineHeight
        }
    }

    private fun inset(r: RectF, d: Float) = RectF(r.left + d, r.top + d, r.right - d, r.bottom - d)

    companion object {
        const val W = 256
        const val H = 192
        private const val HINGE = 24
        private const val KEY_CALIBRATION = "affine"
        private const val DONE_DELAY_MS = 1500L

        /** A touch further than this (in screen pixels) from its target fails calibration. */
        private const val MAX_ERROR = 24f

        private val TARGETS = listOf(Pt(32f, 24f), Pt(224f, 168f), Pt(32f, 168f))

        private val BTN_LEFT = RectF(6f, 166f, 86f, 188f)
        private val BTN_RIGHT = RectF(170f, 166f, 250f, 188f)

        private const val BODY = 0xFF2A2C30.toInt()
        private const val BEZEL = 0xFF0E0E10.toInt()
        private const val HINGE_COLOR = 0xFF1C1D20.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BG = 0xFFF4F4F4.toInt()
        private const val GRID = 0xFFE4E4E4.toInt()
        private const val PANEL = 0xFFFFFFFF.toInt()
        private const val PANEL_BORDER = 0xFFB8B8B8.toInt()
        private const val HEADER_BOTTOM = 0xFFDADADA.toInt()
        private const val FOOTER_BOTTOM = 0xFFBDBDBD.toInt()
        private const val BUTTON_BOTTOM = 0xFFDCDCDC.toInt()
        private const val BUTTON_BORDER = 0xFF9A9A9A.toInt()
        private const val TEXT = 0xFF464646.toInt()
        private const val ACCENT = 0xFF2AA0E6.toInt()
        private const val ACCENT_LIGHT = 0xFF8FD3FA.toInt()
        private const val ACCENT_DARK = 0xFF1573AD.toInt()
        private const val RED = 0xFFE03C31.toInt()
    }
}
