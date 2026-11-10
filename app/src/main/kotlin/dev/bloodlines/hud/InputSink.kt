package dev.bloodlines.hud

/**
 * The seam between the HUD and the game.
 *
 * The HUD is presentation only: it decides *where* controls are, never *what* they do. Every action
 * that reaches the game goes through this interface, which the port implements on top of its
 * existing input bridge. That is what lets the visual redesign ship without touching working
 * mappings (docs/reports/001-technical-investigation.md §8.3).
 *
 * Units:
 *  - [onLook]: look units, already curved/sensitised by [HudGeometry.applyLookCurve].
 *  - [onMove]: x right-positive, y down-positive, magnitude 0..1 (screen convention, as reported).
 */
interface InputSink {

    /** A button went down (pressed = true) or up (pressed = false). Latched actions report taps only. */
    fun onAction(action: HudAction, pressed: Boolean)

    /** Camera delta from a drag over the look region. */
    fun onLook(dx: Float, dy: Float)

    /** Movement stick vector. Sent only when it changes meaningfully. */
    fun onMove(x: Float, y: Float)

    /** Short vibration for a press, if the player left haptics on. */
    fun onHaptic(action: HudAction) {}

    /** Called when the player finishes editing a layout, so it can be persisted. */
    fun onLayoutChanged(layout: HudLayout) {}
}

/** A sink that only logs, for the standalone HUD preview app. */
class LogcatInputSink(
    private val sink: InputSink? = null,
    private val logger: (String) -> Unit = {},
) : InputSink {
    override fun onAction(action: HudAction, pressed: Boolean) {
        logger("action ${action.id} pressed=$pressed")
        sink?.onAction(action, pressed)
    }

    override fun onLook(dx: Float, dy: Float) {
        sink?.onLook(dx, dy)
    }

    override fun onMove(x: Float, y: Float) {
        sink?.onMove(x, y)
    }

    override fun onHaptic(action: HudAction) {
        sink?.onHaptic(action)
    }

    override fun onLayoutChanged(layout: HudLayout) {
        sink?.onLayoutChanged(layout)
    }
}
