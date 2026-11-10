package dev.bloodlines.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Transparent touch-HUD overlay.
 *
 * Drawing is allocation-free in steady state: rectangles, paths and paints are cached and only
 * rebuilt when the layout, the view size or the window insets change. The overlay draws nothing
 * when idle beyond the controls themselves, because the overlay shares the GPU with the game.
 *
 * Pointer routing: each pointer is assigned exactly one role (stick, look, or a specific button) on
 * down and keeps it until it lifts, so a stick drag, a camera drag and a button press can all be
 * active at the same time without stealing each other's events.
 */
class HudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    var sink: InputSink? = null

    var layout: HudLayout = HudLayout.defaults()
        set(value) {
            field = value.clamped()
            recompute()
            invalidate()
        }

    /** Edit mode: buttons become draggable, gameplay input is not forwarded. */
    var editMode: Boolean = false
        set(value) {
            field = value
            selectedAction = null
            dragPointerId = -1
            releaseAllInputs()
            recompute()
            invalidate()
        }

    /** When false the skip control is not drawn nor hit-tested. */
    var cutsceneActive: Boolean = false
        set(value) {
            field = value
            recompute()
            invalidate()
        }

    /** Action currently selected in the editor, if any. */
    var selectedAction: HudAction? = null
        private set

    /** Notified after every drag/resize so the host can persist the layout. */
    var onLayoutEdited: ((HudLayout) -> Unit)? = null

    // ---------------------------------------------------------------- state

    private var insets = HudInsets.NONE
    private var usable = HudRect(0f, 0f, 0f, 0f)
    private var buttonRects: Map<HudAction, HudRect> = emptyMap()
    private var stick = StickMetrics(0f, 0f, 0f, 0f)

    private val pressedActions = HashSet<HudAction>()
    private val latchedActions = HashSet<HudAction>()

    private val pointerRoles = HashMap<Int, Role>()

    // Stick runtime state (the base follows the thumb, so the player never has to find a fixed ring)
    private var stickBaseX = 0f
    private var stickBaseY = 0f
    private var stickKnobX = 0f
    private var stickKnobY = 0f
    private var stickVector = 0f to 0f
    private var stickActive = false

    private var dragPointerId = -1
    private var dragGrabDx = 0f
    private var dragGrabDy = 0f

    private val lookLastX = HashMap<Int, Float>()
    private val lookLastY = HashMap<Int, Float>()

    private sealed class Role {
        object Stick : Role()
        object Look : Role()
        data class Button(val action: HudAction) : Role()
    }

    // ---------------------------------------------------------------- paint / geometry caches

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
    }
    private val path = Path()
    private val tmpRect = RectF()
    private val labels: Map<HudAction, String> =
        HudAction.buttonActions().associateWith { it.label.uppercase() }

    init {
        isClickable = true
        isFocusable = true
        setWillNotDraw(false)
    }

    // ---------------------------------------------------------------- measurement

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recompute()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        this.insets = insets.toHudInsets()
        recompute()
        invalidate()
        return super.onApplyWindowInsets(insets)
    }

    @Suppress("DEPRECATION")
    private fun WindowInsets.toHudInsets(): HudInsets =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            HudInsets(
                insets.left.toFloat(),
                insets.top.toFloat(),
                insets.right.toFloat(),
                insets.bottom.toFloat(),
            )
        } else {
            HudInsets(
                systemWindowInsetLeft.toFloat(),
                systemWindowInsetTop.toFloat(),
                systemWindowInsetRight.toFloat(),
                systemWindowInsetBottom.toFloat(),
            )
        }

    private fun recompute() {
        if (width == 0 || height == 0) return
        usable = HudGeometry.usableArea(width.toFloat(), height.toFloat(), density, insets)
        stick = HudGeometry.stickMetrics(layout, usable, density)
        buttonRects = if (editMode) {
            // In the editor, hidden controls stay visible so they can be re-enabled.
            buildMap {
                for (b in layout.buttons) {
                    if (b.action.isStick || b.action.isLookArea) continue
                    put(b.action, HudGeometry.buttonRect(layout, b, usable, density))
                }
            }
        } else {
            HudGeometry.buttonRects(layout, usable, density).filterKeys {
                it != HudAction.SKIP_CUTSCENE || cutsceneActive || layout.showSkipButton
            }
        }
        if (!stickActive) {
            stickKnobX = stick.centerX
            stickKnobY = stick.centerY
        }
        rebuildPaints()
    }

    private fun rebuildPaints() {
        val opacity = layout.opacity.coerceIn(HudLayout.MIN_OPACITY, 1f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = withAlpha(HudPalette.FILL, opacity)
        rimPaint.color = withAlpha(HudPalette.SHADOW, min(1f, opacity * 0.85f))
        rimPaint.strokeWidth = HudPalette.RIM_DP * density
        labelPaint.textSize = (min(width, height) / density * 0.028f).coerceIn(
            HudPalette.LABEL_MIN_DP,
            HudPalette.LABEL_MAX_DP,
        ) * scaledDensity
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        if (usable.width <= 1f) return
        drawStick(canvas)
        for (b in layout.buttons) {
            val action = b.action
            if (action.isStick || action.isLookArea) continue
            val rect = buttonRects[action] ?: continue
            if (rect.width <= 0f) continue
            val isVisible = b.visible && (!action.isSkip || cutsceneActive || layout.showSkipButton)
            drawButton(canvas, action, rect, isVisible)
        }
        if (editMode) drawEditorHints(canvas)
    }

    private val HudAction.isSkip: Boolean get() = this == HudAction.SKIP_CUTSCENE

    private fun drawStick(canvas: Canvas) {
        if (editMode) return
        val baseAlpha = if (stickActive) 0.95f else 0.35f
        strokePaint.color = withAlpha(HudPalette.BONE, baseAlpha * layout.opacity)
        strokePaint.strokeWidth = (if (stickActive) 1.8f else 1.2f) * density
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius, strokePaint)

        fillPaint.color = withAlpha(HudPalette.FILL, layout.opacity * 0.55f)
        canvas.drawCircle(stick.centerX, stick.centerY, stick.radius, fillPaint)

        val knobRadius = stick.radius * 0.42f
        fillPaint.color = withAlpha(HudPalette.FILL, layout.opacity * 0.9f)
        canvas.drawCircle(stickKnobX, stickKnobY, knobRadius, fillPaint)
        strokePaint.color = withAlpha(
            if (stickActive) HudPalette.BONE else HudPalette.ASH,
            layout.opacity,
        )
        strokePaint.strokeWidth = (if (stickActive) 2f else 1.2f) * density
        canvas.drawCircle(stickKnobX, stickKnobY, knobRadius, strokePaint)
    }

    private fun drawButton(canvas: Canvas, action: HudAction, rect: HudRect, visible: Boolean) {
        val isPressed = action in pressedActions || action in latchedActions
        val isSelected = editMode && selectedAction == action
        val scale = if (isPressed && !editMode) HudPalette.PRESS_SCALE else 1f
        val halfW = rect.width * 0.5f * scale
        val halfH = rect.height * 0.5f * scale
        tmpRect.set(rect.centerX - halfW, rect.centerY - halfH, rect.centerX + halfW, rect.centerY + halfH)

        val alpha = layout.opacity * (if (visible) 1f else 0.3f)

        // Rim first: a dark halo keeps the shape readable over bright scenes.
        buildPath(action, tmpRect)
        rimPaint.color = withAlpha(HudPalette.SHADOW, alpha * 0.9f)
        rimPaint.strokeWidth = HudPalette.RIM_DP * density
        canvas.drawPath(path, rimPaint)

        fillPaint.color = withAlpha(HudPalette.FILL, alpha * if (isPressed) 1f else 0.92f)
        canvas.drawPath(path, fillPaint)

        strokePaint.color = when {
            isSelected -> HudPalette.ASH
            isPressed -> HudPalette.BLOOD
            else -> withAlpha(HudPalette.BONE, alpha)
        }
        strokePaint.strokeWidth =
            (if (isPressed || isSelected) HudPalette.STROKE_ACTIVE_DP else HudPalette.STROKE_IDLE_DP) * density
        canvas.drawPath(path, strokePaint)

        labelPaint.color = when {
            isPressed -> HudPalette.BLOOD
            else -> withAlpha(HudPalette.BONE, min(1f, alpha + 0.1f))
        }
        val label = labels[action] ?: action.label
        val textY = tmpRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) * 0.5f
        canvas.drawText(label, tmpRect.centerX(), textY, labelPaint)

        if (isSelected) {
            strokePaint.color = HudPalette.BLOOD
            strokePaint.strokeWidth = 1f * density
            canvas.drawCircle(rect.centerX, rect.centerY, rect.width * 0.72f, strokePaint)
        }
    }

    private fun buildPath(action: HudAction, r: RectF) {
        path.reset()
        when (action.family) {
            HudFamily.COMBAT -> {
                // Blade-ish hexagon: distinct from every other family at a glance.
                val notch = r.width() * 0.13f
                path.moveTo(r.left + notch, r.top)
                path.lineTo(r.right - notch, r.top)
                path.lineTo(r.right, r.centerY())
                path.lineTo(r.right - notch, r.bottom)
                path.lineTo(r.left + notch, r.bottom)
                path.lineTo(r.left, r.centerY())
                path.close()
            }
            HudFamily.INTERACTION -> {
                path.addRoundRect(r, r.width() * 0.28f, r.width() * 0.28f, Path.Direction.CW)
            }
            HudFamily.META -> {
                path.addRoundRect(r, r.width() * 0.22f, r.width() * 0.22f, Path.Direction.CW)
            }
            HudFamily.MOVEMENT -> {
                path.addCircle(r.centerX(), r.centerY(), r.width() * 0.5f, Path.Direction.CW)
            }
            HudFamily.SYSTEM -> {
                path.addRoundRect(r, r.height() * 0.45f, r.height() * 0.45f, Path.Direction.CW)
            }
        }
    }

    private fun drawEditorHints(canvas: Canvas) {
        strokePaint.color = withAlpha(HudPalette.ASH, 0.35f)
        strokePaint.strokeWidth = 1f * density
        tmpRect.set(usable.left, usable.top, usable.right, usable.bottom)
        canvas.drawRect(tmpRect, strokePaint)
        val keep = HudGeometry.centreKeepClear(usable)
        strokePaint.color = withAlpha(HudPalette.BLOOD_DIM, 0.5f)
        tmpRect.set(keep.left, keep.top, keep.right, keep.bottom)
        canvas.drawRect(tmpRect, strokePaint)
        strokePaint.color = withAlpha(HudPalette.ASH, 0.25f)
        val step = 40f * density
        var x = usable.left
        while (x < usable.right) {
            canvas.drawLine(x, usable.top, x, usable.bottom, strokePaint)
            x += step
        }
        var y = usable.top
        while (y < usable.bottom) {
            canvas.drawLine(usable.left, y, usable.right, y, strokePaint)
            y += step
        }
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                onPointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    onPointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                onPointerUp(event.getPointerId(index))
                if (event.actionMasked == MotionEvent.ACTION_UP) releaseAllInputs()
            }
            MotionEvent.ACTION_CANCEL -> releaseAllInputs()
        }
        return true
    }

    private fun onPointerDown(id: Int, x: Float, y: Float) {
        if (editMode) {
            handleEditorDown(id, x, y)
            return
        }
        // Smallest matching button wins, so a small control on top of a larger one stays usable.
        val hit = buttonRects.entries
            .filter { it.value.contains(x, y) }
            .minByOrNull { it.value.width }
        if (hit != null) {
            val action = hit.key
            pointerRoles[id] = Role.Button(action)
            if (action.latched) {
                val nowOn = latchedActions.toggle(action)
                sink?.onAction(action, nowOn)
                haptic(action)
            } else {
                pressedActions.add(action)
                sink?.onAction(action, true)
                haptic(action)
            }
            invalidate()
            return
        }
        if (inStickZone(x, y)) {
            stickActive = true
            stickBaseX = x
            stickBaseY = y
            pointerRoles[id] = Role.Stick
            updateStick(x, y)
            invalidate()
            return
        }
        if (HudGeometry.lookArea(usable).contains(x, y)) {
            pointerRoles[id] = Role.Look
            lookLastX[id] = x
            lookLastY[id] = y
        }
    }

    private fun inStickZone(x: Float, y: Float): Boolean {
        val reach = stick.radius * 1.9f
        if (hypot(x - stick.centerX, y - stick.centerY) <= reach) return true
        // Generous fallback zone: the left third, below the top quarter, so the thumb never misses it.
        return x <= usable.left + usable.width * HudGeometry.MOVEMENT_COLUMN_FRACTION &&
            y >= usable.top + usable.height * 0.25f
    }

    private fun onPointerMove(id: Int, x: Float, y: Float) {
        if (editMode) {
            handleEditorMove(id, x, y)
            return
        }
        when (val role = pointerRoles[id]) {
            Role.Stick -> updateStick(x, y)
            Role.Look -> {
                val lastX = lookLastX[id] ?: x
                val lastY = lookLastY[id] ?: y
                lookLastX[id] = x
                lookLastY[id] = y
                val dx = HudGeometry.applyLookCurve(x - lastX, layout, density)
                val dy = HudGeometry.applyLookCurve(y - lastY, layout, density)
                if (dx != 0f || dy != 0f) sink?.onLook(dx, dy)
            }
            is Role.Button -> {
                // A finger that slides off a button releases it, so it cannot get stuck down.
                val rect = buttonRects[role.action] ?: return
                if (!rect.contains(x, y) && role.action !in latchedActions) {
                    if (pressedActions.remove(role.action)) {
                        sink?.onAction(role.action, false)
                        invalidate()
                    }
                }
            }
            null -> Unit
        }
    }

    private fun onPointerUp(id: Int) {
        when (val role = pointerRoles.remove(id)) {
            Role.Stick -> {
                stickActive = false
                stickVector = 0f to 0f
                sink?.onMove(0f, 0f)
                stickKnobX = stick.centerX
                stickKnobY = stick.centerY
                invalidate()
            }
            Role.Look -> {
                lookLastX.remove(id)
                lookLastY.remove(id)
            }
            is Role.Button -> {
                if (pressedActions.remove(role.action)) {
                    sink?.onAction(role.action, false)
                }
                invalidate()
            }
            null -> Unit
        }
    }

    private fun updateStick(x: Float, y: Float) {
        val dx = x - stickBaseX
        val dy = y - stickBaseY
        val vector = HudGeometry.stickVector(dx, dy, stick)
        val (vx, vy) = vector
        // Clamp the knob into the ring for rendering.
        val length = hypot(dx, dy)
        if (length <= stick.radius || length == 0f) {
            stickKnobX = x
            stickKnobY = y
        } else {
            stickKnobX = stickBaseX + dx / length * stick.radius
            stickKnobY = stickBaseY + dy / length * stick.radius
        }
        if (abs(vx - stickVector.first) > 0.02f || abs(vy - stickVector.second) > 0.02f) {
            stickVector = vx to vy
            sink?.onMove(vx, vy)
        }
        invalidate()
    }

    private fun releaseAllInputs() {
        for (action in pressedActions) sink?.onAction(action, false)
        pressedActions.clear()
        if (stickActive) {
            stickActive = false
            sink?.onMove(0f, 0f)
        }
        stickVector = 0f to 0f
        stickKnobX = stick.centerX
        stickKnobY = stick.centerY
        pointerRoles.clear()
        lookLastX.clear()
        lookLastY.clear()
        dragPointerId = -1
        invalidate()
    }

    private fun haptic(action: HudAction) {
        if (!layout.haptics) return
        performHapticFeedback(
            when (action.family) {
                HudFamily.COMBAT -> HapticFeedbackConstants.VIRTUAL_KEY
                else -> HapticFeedbackConstants.KEYBOARD_TAP
            },
        )
    }

    // ---------------------------------------------------------------- editor

    /** Selects [action] for editing (or null to clear). */
    fun selectForEditing(action: HudAction?) {
        selectedAction = action
        invalidate()
    }

    private fun handleEditorDown(id: Int, x: Float, y: Float) {
        val hit = buttonRects.entries.filter { it.value.contains(x, y) }.minByOrNull { it.value.width }
        if (hit != null) {
            selectedAction = hit.key
            dragPointerId = id
            dragGrabDx = x - hit.value.centerX
            dragGrabDy = y - hit.value.centerY
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } else {
            selectedAction = null
        }
        invalidate()
    }

    private fun handleEditorMove(id: Int, x: Float, y: Float) {
        val action = selectedAction ?: return
        if (dragPointerId != id) return
        val size = HudGeometry.buttonSizePx(this.layout, usable, density, sizeDpFor(action))
        val (cx, cy) = HudGeometry.clampButtonCentre(x - dragGrabDx, y - dragGrabDy, size, usable)
        moveButtonTo(action, cx, cy)
    }

    private fun sizeDpFor(action: HudAction): Float =
        layout.button(action)?.sizeDp ?: HudLayout.MIN_BUTTON_DP

    /** Moves a button to a pixel centre, keeping it anchored to its nearest corner. */
    fun moveButtonTo(action: HudAction, centerX: Float, centerY: Float) {
        val button = layout.button(action) ?: return
        val size = HudGeometry.buttonSizePx(layout, usable, density, button.sizeDp)
        val anchor = if (editMode) HudGeometry.anchorFor(centerX, centerY, usable) else button.anchor
        val (ox, oy) = HudGeometry.offsetsFor(anchor, centerX, centerY, size, usable, density)
        layout = layout.withButton(action) { it.copy(anchor = anchor, offsetXDp = ox, offsetYDp = oy) }
        onLayoutEdited?.invoke(layout)
    }

    /** Resizes the selected button by [deltaDp]. */
    fun resizeSelected(deltaDp: Float) {
        val action = selectedAction ?: return
        layout = layout.withButton(action) { it.copy(sizeDp = it.sizeDp + deltaDp) }
        onLayoutEdited?.invoke(layout)
    }

    /** Shows/hides a single control from the editor. */
    fun setActionVisible(action: HudAction, visible: Boolean) {
        layout = layout.withButton(action) { it.copy(visible = visible) }
        onLayoutEdited?.invoke(layout)
    }

    fun restoreDefaults() {
        layout = HudLayout.defaults()
        selectedAction = null
        onLayoutEdited?.invoke(layout)
    }

    private fun MutableSet<HudAction>.toggle(action: HudAction): Boolean =
        if (contains(action)) {
            remove(action)
            false
        } else {
            add(action)
            true
        }
}
