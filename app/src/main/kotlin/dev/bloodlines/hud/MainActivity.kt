package dev.bloodlines.hud

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.sin

/**
 * Standalone HUD preview and layout customiser.
 *
 * This activity exists so the control redesign can be designed, tested and tuned on the real device
 * without the game: it hosts the same [HudOverlayView] the port will embed, over a stand-in scene
 * that reacts to the stick and to look drags. Once the port is available, the overlay, the layout
 * model and the sink are reused unchanged; only [InputSink] is wired to the real input bridge.
 */
class MainActivity : Activity() {

    private lateinit var hud: HudOverlayView
    private lateinit var backdrop: BackdropView
    private lateinit var store: LayoutStore
    private lateinit var panel: LinearLayout
    private lateinit var status: TextView

    private var bucket = AspectBucket.R16_9
    private var loaded = false
    private var panelOpen = false
    private var cutsceneSim = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersive()

        store = LayoutStore(this)

        val root = FrameLayout(this)
        backdrop = BackdropView(this)
        root.addView(backdrop, matchParent())

        hud = HudOverlayView(this)
        hud.sink = object : InputSink {
            private var lastLog = 0L
            override fun onAction(action: HudAction, pressed: Boolean) {
                val now = System.currentTimeMillis()
                if (now - lastLog > 120) {
                    lastLog = now
                    Log.i(TAG, "action=${action.id} pressed=$pressed")
                }
            }

            override fun onLook(dx: Float, dy: Float) = backdrop.applyLook(dx, dy)

            override fun onMove(x: Float, y: Float) = backdrop.applyMove(x, y)

            override fun onLayoutChanged(layout: HudLayout) = store.save(bucket, layout)
        }
        hud.onLayoutEdited = { layout -> store.save(bucket, layout) }
        root.addView(hud, matchParent())

        panel = buildPanel()
        root.addView(panel, matchParent())

        val gear = Button(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(HudPalette.BONE)
            background = roundedBackground(0.85f)
            setOnClickListener { togglePanel() }
        }
        val gearParams = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        gearParams.topMargin = dp(6)
        root.addView(gear, gearParams)

        setContentView(root)
        panel.visibility = View.GONE

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshBucket() }
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        refreshBucket()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    // ---------------------------------------------------------------- state

    private fun refreshBucket() {
        val newBucket = AspectBucket.of(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
        )
        if (!loaded || newBucket != bucket) {
            loaded = true
            bucket = newBucket
            hud.layout = store.load(bucket)
        }
        updateStatus()
    }

    private fun updateStatus() {
        val m = resources.displayMetrics
        val usable = HudGeometry.usableArea(
            m.widthPixels.toFloat(),
            m.heightPixels.toFloat(),
            m.density,
            HudInsets.NONE,
        )
        val problems = HudGeometry.validate(hud.layout, usable, m.density)
        val size = "${m.widthPixels}×${m.heightPixels} @${"%.2f".format(m.density)}x"
        val bucketName = bucket.id
        status.text = if (problems.isEmpty()) {
            "$size · $bucketName · layout OK"
        } else {
            "$size · $bucketName · ${problems.size} issue(s): ${problems.first()}"
        }
    }

    private fun togglePanel() {
        panelOpen = !panelOpen
        panel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        if (!panelOpen && hud.editMode) hud.editMode = false
    }

    // ---------------------------------------------------------------- settings panel

    private fun buildPanel(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(0.92f)
        }

        val title = TextView(this).apply {
            text = "HUD settings"
            setTextColor(HudPalette.BONE)
            textSize = 16f
            setPadding(0, 0, 0, dp(4))
        }
        card.addView(title)

        status = TextView(this).apply {
            setTextColor(HudPalette.ASH)
            textSize = 11f
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(status)

        card.addView(slider("Opacity", 25f, 100f, hud.layout.opacity * 100f) { value ->
            hud.layout = hud.layout.copy(opacity = value / 100f)
        })
        card.addView(slider("Button size", 70f, 160f, hud.layout.buttonScale * 100f) { value ->
            hud.layout = hud.layout.copy(buttonScale = value / 100f)
        })
        card.addView(slider("Stick radius", 40f, 110f, hud.layout.stickRadiusDp) { value ->
            hud.layout = hud.layout.copy(stickRadiusDp = value)
        })
        card.addView(slider("Look sensitivity", 20f, 300f, hud.layout.lookSensitivity * 100f) { value ->
            hud.layout = hud.layout.copy(lookSensitivity = value / 100f)
        })
        card.addView(slider("Look precision", 100f, 250f, hud.layout.lookExponent * 100f) { value ->
            hud.layout = hud.layout.copy(lookExponent = value / 100f)
        })

        card.addView(toggle("Haptics", hud.layout.haptics) { on ->
            hud.layout = hud.layout.copy(haptics = on)
        })
        card.addView(toggle("Cutscene skip button", hud.layout.showSkipButton) { on ->
            hud.layout = hud.layout.copy(showSkipButton = on)
        })
        card.addView(toggle("Simulate cutscene", cutsceneSim) { on ->
            cutsceneSim = on
            hud.cutsceneActive = on
            backdrop.cutscene = on
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(button("Edit layout") {
            hud.editMode = !hud.editMode
            status.text = if (hud.editMode) {
                "Drag a control to move it. Tap it, then use Size ± / Hide."
            } else {
                "Editing finished"
            }
        })
        actions.addView(button("Size −") { hud.resizeSelected(-4f) })
        actions.addView(button("Size +") { hud.resizeSelected(4f) })
        card.addView(actions)

        val actions2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actions2.addView(button("Save") {
            store.save(bucket, hud.layout)
            status.text = "Saved layout for ${bucket.id}"
        })
        actions2.addView(button("Hide selected") {
            hud.selectedAction?.let { hud.setActionVisible(it, false) }
        })
        actions2.addView(button("Restore defaults") {
            hud.restoreDefaults()
            store.reset(bucket)
            updateStatus()
        })
        card.addView(actions2)

        card.addView(button("Close") { togglePanel() })

        val scroll = ScrollView(this).apply { addView(card) }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(10))
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { width = dp(460) },
            )
        }
        return wrapper
    }

    private fun slider(
        label: String,
        min: Float,
        max: Float,
        initial: Float,
        onChange: (Float) -> Unit,
    ): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val caption = TextView(this).apply {
            text = label
            setTextColor(HudPalette.ASH)
            textSize = 12f
        }
        val bar = SeekBar(this).apply {
            this.max = 1000
            progress = (((initial - min) / (max - min)) * 1000f).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = min + (max - min) * (progress / 1000f)
                    caption.text = "$label: ${"%.2f".format(value)}"
                    onChange(value)
                    updateStatus()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    store.save(bucket, hud.layout)
                }
            })
        }
        caption.text = "$label: ${"%.2f".format(initial)}"
        wrapper.addView(caption)
        wrapper.addView(bar)
        return wrapper
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val switch = Switch(this).apply {
            text = label
            isChecked = initial
            setTextColor(HudPalette.ASH)
            textSize = 12f
            setOnCheckedChangeListener { _, checked ->
                onChange(checked)
                store.save(bucket, hud.layout)
            }
        }
        return switch
    }

    private fun button(label: String, onClick: () -> Unit): View {
        val b = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(HudPalette.BONE)
            background = roundedBackground(0.8f)
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        params.marginEnd = dp(6)
        b.layoutParams = params
        return b
    }

    private fun roundedBackground(alpha: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(Color.argb((255 * alpha).toInt(), 12, 12, 14))
        setStroke(dp(1), Color.argb((255 * 0.55f).toInt(), 232, 226, 214))
    }

    // ---------------------------------------------------------------- window

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- stand-in scene

    /**
     * A cheap painted stand-in for the game: gothic skyline, moon, fog, and a crosshair. It reacts to
     * the stick (walk bob) and to look drags (parallax pan) so the controls can be judged by feel
     * without launching the game.
     */
    private class BackdropView(context: Context) : View(context) {

        var cutscene = false
            set(value) {
                field = value
                invalidate()
            }

        private var panX = 0f
        private var panY = 0f
        private var moveX = 0f
        private var moveY = 0f
        private var phase = 0f

        private val skyPaint = Paint()
        private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val buildingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.argb(140, 232, 226, 214)
        }
        private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barPaint = Paint().apply { color = Color.BLACK }
        private val path = Path()
        private var shadersReady = false

        fun applyLook(dx: Float, dy: Float) {
            panX = (panX + dx * 6f).coerceIn(-260f, 260f)
            panY = (panY + dy * 6f).coerceIn(-120f, 120f)
            invalidate()
        }

        fun applyMove(x: Float, y: Float) {
            moveX = x
            moveY = y
            phase += 0.15f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            if (!shadersReady) {
                skyPaint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(
                        Color.rgb(9, 11, 18),
                        Color.rgb(22, 20, 30),
                        Color.rgb(40, 28, 32),
                        Color.rgb(12, 10, 12),
                    ),
                    floatArrayOf(0f, 0.45f, 0.72f, 1f),
                    Shader.TileMode.CLAMP,
                )
                vignettePaint.shader = RadialGradient(
                    w * 0.5f, h * 0.5f, h * 0.95f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(200, 0, 0, 0)),
                    floatArrayOf(0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
                shadersReady = true
            }
            canvas.drawRect(0f, 0f, w, h, skyPaint)

            // Moon, panning slowly (parallax layer 0)
            val moonX = w * 0.66f - panX * 0.25f
            val moonY = h * 0.22f - panY * 0.2f
            moonPaint.color = Color.argb(230, 226, 222, 206)
            canvas.drawCircle(moonX, moonY, h * 0.075f, moonPaint)
            moonPaint.color = Color.argb(28, 226, 222, 206)
            canvas.drawCircle(moonX, moonY, h * 0.16f, moonPaint)

            // Skyline (parallax layer 1)
            buildingPaint.color = Color.rgb(6, 6, 9)
            val base = h * 0.86f
            val offset = -panX * 0.5f
            var x = -80f + (offset % 120f)
            var i = 0
            while (x < w + 120f) {
                val seed = ((i * 37) % 11) / 11f
                val bw = 60f + seed * 90f
                val bh = h * (0.10f + seed * 0.22f)
                path.reset()
                path.moveTo(x, base)
                path.lineTo(x, base - bh)
                path.lineTo(x + bw * 0.5f, base - bh - h * 0.05f)
                path.lineTo(x + bw, base - bh)
                path.lineTo(x + bw, base)
                path.close()
                canvas.drawPath(path, buildingPaint)
                x += bw * 0.9f
                i++
            }

            // Street / fog
            fogPaint.color = Color.argb(70, 60, 52, 58)
            canvas.drawRect(0f, base, w, h, fogPaint)
            fogPaint.color = Color.argb(26, 120, 112, 120)
            val bob = if (moveY < -0.2f) sin(phase) * h * 0.004f else 0f
            canvas.drawRect(0f, base + h * 0.06f + bob, w, base + h * 0.16f + bob, fogPaint)

            // Crosshair (the centre the HUD must keep clear)
            val cx = w * 0.5f - moveX * 6f
            val cy = h * 0.5f - moveY * 6f
            val r = h * 0.012f
            canvas.drawCircle(cx, cy, r, crossPaint)
            canvas.drawLine(cx - r * 2.4f, cy, cx - r * 1.2f, cy, crossPaint)
            canvas.drawLine(cx + r * 1.2f, cy, cx + r * 2.4f, cy, crossPaint)
            canvas.drawLine(cx, cy - r * 2.4f, cx, cy - r * 1.2f, crossPaint)
            canvas.drawLine(cx, cy + r * 1.2f, cx, cy + r * 2.4f, crossPaint)

            if (cutscene) {
                canvas.drawRect(0f, 0f, w, h * 0.10f, barPaint)
                canvas.drawRect(0f, h * 0.90f, w, h, barPaint)
            }

            canvas.drawRect(0f, 0f, w, h, vignettePaint)
        }
    }

    private companion object {
        const val TAG = "BloodlinesHud"
    }
}
